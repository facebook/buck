/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.artifact_cache;

import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.counters.SamplingCounter;
import com.facebook.buck.counters.TagSetCounter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@link DirArtifactCache} and {@link HttpArtifactCache} caches use a straightforward rulekey
 * -> (metadata, artifact) mapping. This works well and is easy to reason about. That also means
 * that we will fetch `artifact` whenever they `key` or `metadata` change possibly resulting in
 * fetching the same artifact multiple times. This class is an attempt at reducing the number of
 * repeated fetches for the same artifact. The data is stored and retrieved using the following
 * scheme: rulekey -> (metadata, content hash) content hash -> artifact This means we only download
 * the artifact when its contents change. This means that rules with different keys but identical
 * outputs require less network bandwidth at the expense of doubling latency for downloading rules
 * whose outputs we had not yet seen.
 */
public class TwoLevelArtifactCacheDecorator implements ArtifactCache, CacheDecorator {

  @VisibleForTesting static final String METADATA_KEY = "TWO_LEVEL_CACHE_CONTENT_HASH";
  private static final String COUNTER_CATEGORY = "buck_two_level_cache_stats";

  private static final Logger LOG = Logger.get(TwoLevelArtifactCacheDecorator.class);

  private final ArtifactCache delegate;
  private final ProjectFilesystem projectFilesystem;
  private final Path emptyFilePath;
  private final boolean performTwoLevelStores;
  private final long minimumTwoLevelStoredArtifactSize;
  private final Optional<Long> maximumTwoLevelStoredArtifactSize;

  private final TagSetCounter secondLevelCacheHitTypes;
  private final SamplingCounter secondLevelCacheHitBytes;
  private final IntegerCounter secondLevelCacheMisses;
  private final SamplingCounter secondLevelHashComputationTimeMs;

  public TwoLevelArtifactCacheDecorator(
      ArtifactCache delegate,
      ProjectFilesystem projectFilesystem,
      BuckEventBus buckEventBus,
      boolean performTwoLevelStores,
      long minimumTwoLevelStoredArtifactSize,
      Optional<Long> maximumTwoLevelStoredArtifactSize) {
    this.delegate = delegate;
    this.projectFilesystem = projectFilesystem;
    this.performTwoLevelStores = performTwoLevelStores;
    this.minimumTwoLevelStoredArtifactSize = minimumTwoLevelStoredArtifactSize;
    this.maximumTwoLevelStoredArtifactSize = maximumTwoLevelStoredArtifactSize;

    Path tmpDir = projectFilesystem.getBuckPaths().getTmpDir();
    try {
      projectFilesystem.mkdirs(tmpDir);
      this.emptyFilePath =
          projectFilesystem.resolve(
              projectFilesystem.createTempFile(tmpDir, ".buckcache", ".empty"));
    } catch (IOException e) {
      throw new HumanReadableException(
          "Could not create file in " + projectFilesystem.resolve(tmpDir));
    }

    secondLevelCacheHitTypes =
        new TagSetCounter(COUNTER_CATEGORY, "second_level_cache_hit_types", ImmutableMap.of());
    secondLevelCacheHitBytes =
        new SamplingCounter(COUNTER_CATEGORY, "second_level_cache_hit_bytes", ImmutableMap.of());
    secondLevelCacheMisses =
        new IntegerCounter(COUNTER_CATEGORY, "second_level_cache_misses", ImmutableMap.of());
    secondLevelHashComputationTimeMs =
        new SamplingCounter(
            COUNTER_CATEGORY, "second_level_hash_computation_time_ms", ImmutableMap.of());
    buckEventBus.post(
        new CounterRegistry.AsyncCounterRegistrationEvent(
            ImmutableList.of(
                secondLevelCacheHitTypes,
                secondLevelCacheHitBytes,
                secondLevelCacheMisses,
                secondLevelHashComputationTimeMs)));
  }

  @Override
  public ListenableFuture<CacheResult> fetchAsync(RuleKey ruleKey, LazyPath output) {
    return Futures.transformAsync(
        delegate.fetchAsync(ruleKey, output),
        (CacheResult fetchResult) -> {
          if (!fetchResult.getType().isSuccess()) {
            LOG.verbose("Missed first-level lookup.");
            return Futures.immediateFuture(fetchResult);
          } else if (!fetchResult.getMetadata().containsKey(METADATA_KEY)) {
            LOG.verbose("Found a single-level entry.");
            return Futures.immediateFuture(fetchResult);
          }
          LOG.verbose("Found a first-level artifact with metadata: %s", fetchResult.getMetadata());

          String contentHashKey = fetchResult.getMetadata().get(METADATA_KEY);
          ListenableFuture<CacheResult> outputFileFetchResultFuture =
              delegate.fetchAsync(new RuleKey(contentHashKey), output);

          return Futures.transformAsync(
              outputFileFetchResultFuture,
              (CacheResult outputFileFetchResult) -> {
                outputFileFetchResult =
                    outputFileFetchResult.withTwoLevelContentHashKey(contentHashKey);

                if (!outputFileFetchResult.getType().isSuccess()) {
                  LOG.verbose("Missed second-level lookup.");
                  secondLevelCacheMisses.inc();

                  // Note: for misses, the fetchResult metadata is not important, so we return
                  // outputFileFetchResult to signal the miss (as fetchResult was a hit).
                  return Futures.immediateFuture(outputFileFetchResult);
                }

                if (outputFileFetchResult.cacheSource().isPresent()) {
                  secondLevelCacheHitTypes.add(outputFileFetchResult.cacheSource().get());
                }
                if (outputFileFetchResult.artifactSizeBytes().isPresent()) {
                  secondLevelCacheHitBytes.addSample(
                      outputFileFetchResult.artifactSizeBytes().get());
                }

                LOG.verbose(
                    "Found a second-level artifact with metadata: %s",
                    outputFileFetchResult.getMetadata());
                // Note: in the case of a hit, we return fetchResult, rather than
                // outputFileFetchResult,
                // so that the client gets the correct metadata.
                CacheResult finalResult = fetchResult.withTwoLevelContentHashKey(contentHashKey);

                // The two level content hash was not part of the original metadata that was stored
                // to the cache, don't include it in the result.
                finalResult =
                    finalResult.withMetadata(
                        ImmutableMap.copyOf(
                            RichStream.from(finalResult.getMetadata().entrySet())
                                .filter(e -> !Objects.equals(e.getKey(), METADATA_KEY))
                                .toOnceIterable()));
                return Futures.immediateFuture(finalResult);
              },
              MoreExecutors.directExecutor());
        },
        MoreExecutors.directExecutor());
  }

  @Override
  public void skipPendingAndFutureAsyncFetches() {
    delegate.skipPendingAndFutureAsyncFetches();
  }

  @Override
  public ListenableFuture<Void> store(final ArtifactInfo info, final BorrowablePath output) {

    return Futures.transformAsync(
        attemptTwoLevelStore(info, output),
        input -> {
          if (input) {
            return Futures.immediateFuture(null);
          }
          return delegate.store(info, output);
        });
  }

  /**
   * Contains is supposed to be best-effort, but super-fast => Assume the second level is present.
   */
  @Override
  public ListenableFuture<ImmutableMap<RuleKey, CacheResult>> multiContainsAsync(
      ImmutableSet<RuleKey> ruleKeys) {
    return delegate.multiContainsAsync(ruleKeys);
  }

  @Override
  public ListenableFuture<CacheDeleteResult> deleteAsync(List<RuleKey> ruleKeys) {
    // Artifact can be stored as two-level entry (rule key -> hash -> content)
    // and delete operation only deletes first level (rule key -> hash) in that case.
    return delegate.deleteAsync(ruleKeys);
  }

  @Override
  public ArtifactCache getDelegate() {
    return delegate;
  }

  private ListenableFuture<Boolean> attemptTwoLevelStore(
      final ArtifactInfo info, final BorrowablePath output) {

    return Futures.transformAsync(
        Futures.immediateFuture(null),
        (AsyncFunction<Void, Boolean>)
            input -> {
              long fileSize = projectFilesystem.getFileSize(output.getPath());

              if (!performTwoLevelStores
                  || fileSize < minimumTwoLevelStoredArtifactSize
                  || (maximumTwoLevelStoredArtifactSize.isPresent()
                      && fileSize > maximumTwoLevelStoredArtifactSize.get())) {
                return Futures.immediateFuture(false);
              }

              long hashComputationStart = System.currentTimeMillis();
              String hashCode = projectFilesystem.computeSha1(output.getPath()) + "2c00";
              long hashComputationEnd = System.currentTimeMillis();
              secondLevelHashComputationTimeMs.addSample(hashComputationEnd - hashComputationStart);

              ImmutableMap<String, String> metadataWithCacheKey =
                  ImmutableMap.<String, String>builder()
                      .putAll(info.getMetadata())
                      .put(METADATA_KEY, hashCode)
                      .build();

              return Futures.transform(
                  Futures.allAsList(
                      delegate.store(
                          ArtifactInfo.builder()
                              .setRuleKeys(info.getRuleKeys())
                              .setMetadata(metadataWithCacheKey)
                              .build(),
                          BorrowablePath.notBorrowablePath(emptyFilePath)),
                      delegate.store(
                          ArtifactInfo.builder().addRuleKeys(new RuleKey(hashCode)).build(),
                          output)),
                  Functions.constant(true));
            });
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return delegate.getCacheReadMode();
  }

  @Override
  public void close() {
    delegate.close();
    try {
      projectFilesystem.deleteFileAtPath(emptyFilePath);
    } catch (IOException e) {
      LOG.debug("Exception when deleting temp file %s.", emptyFilePath, e);
    }
  }
}
