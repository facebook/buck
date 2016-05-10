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

import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.counters.SamplingCounter;
import com.facebook.buck.counters.TagSetCounter;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The {@link DirArtifactCache} and {@link HttpArtifactCache} caches use a straightforward
 * rulekey -> (metadata, artifact) mapping. This works well and is easy to reason about.
 * That also means that we will fetch `artifact` whenever they `key` or `metadata` change possibly
 * resulting in fetching the same artifact multiple times.
 * This class is an attempt at reducing the number of repeated fetches for the same artifact. The
 * data is stored and retrieved using the following scheme:
 *   rulekey -> (metadata, content hash)
 *   content hash -> artifact
 * This means we only download the artifact when its contents change. This means that rules with
 * different keys but identical outputs require less network bandwidth at the expense of doubling
 * latency for downloading rules whose outputs we had not yet seen.
 */
public class TwoLevelArtifactCacheDecorator implements ArtifactCache {

  @VisibleForTesting
  static final String METADATA_KEY = "TWO_LEVEL_CACHE_CONTENT_HASH";
  private static final String COUNTER_CATEGORY = "buck_two_level_cache_stats";

  private static final Logger LOG = Logger.get(TwoLevelArtifactCacheDecorator.class);

  private final ArtifactCache delegate;
  private final ProjectFilesystem projectFilesystem;
  private final ListeningExecutorService listeningExecutorService;
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
      ListeningExecutorService listeningExecutorService,
      boolean performTwoLevelStores,
      long minimumTwoLevelStoredArtifactSize,
      Optional<Long> maximumTwoLevelStoredArtifactSize) {
    this.delegate = delegate;
    this.projectFilesystem = projectFilesystem;
    this.listeningExecutorService = listeningExecutorService;
    this.performTwoLevelStores = performTwoLevelStores;
    this.minimumTwoLevelStoredArtifactSize = minimumTwoLevelStoredArtifactSize;
    this.maximumTwoLevelStoredArtifactSize = maximumTwoLevelStoredArtifactSize;
    Path scratchDir = projectFilesystem.getBuckPaths().getScratchDir();
    try {
      projectFilesystem.mkdirs(scratchDir);
      this.emptyFilePath = projectFilesystem.resolve(
          projectFilesystem.createTempFile(
              scratchDir,
              ".buckcache",
              ".empty"));
    } catch (IOException e) {
      throw new HumanReadableException(
          "Could not create file in " + projectFilesystem.resolve(scratchDir));
    }

    secondLevelCacheHitTypes = new TagSetCounter(
        COUNTER_CATEGORY,
        "second_level_cache_hit_types",
        ImmutableMap.<String, String>of());
    secondLevelCacheHitBytes = new SamplingCounter(
        COUNTER_CATEGORY,
        "second_level_cache_hit_bytes",
        ImmutableMap.<String, String>of());
    secondLevelCacheMisses = new IntegerCounter(
        COUNTER_CATEGORY,
        "second_level_cache_misses",
        ImmutableMap.<String, String>of());
    secondLevelHashComputationTimeMs = new SamplingCounter(
        COUNTER_CATEGORY,
        "second_level_hash_computation_time_ms",
        ImmutableMap.<String, String>of());
    buckEventBus.post(new CounterRegistry.AsyncCounterRegistrationEvent(
        ImmutableSet.of(
            secondLevelCacheHitTypes,
            secondLevelCacheHitBytes,
            secondLevelCacheMisses,
            secondLevelHashComputationTimeMs)));
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    CacheResult fetchResult = delegate.fetch(ruleKey, output);
    if (!fetchResult.getType().isSuccess() ||
        !fetchResult.getMetadata().containsKey(METADATA_KEY)) {
      return fetchResult;
    }
    CacheResult outputFileFetchResult = delegate.fetch(
        new RuleKey(fetchResult.getMetadata().get(METADATA_KEY)),
        output);
    if (!outputFileFetchResult.getType().isSuccess()) {
      secondLevelCacheMisses.inc();
      return outputFileFetchResult;
    }

    if (outputFileFetchResult.cacheSource().isPresent()) {
      secondLevelCacheHitTypes.add(outputFileFetchResult.cacheSource().get());
    }
    if (outputFileFetchResult.artifactSizeBytes().isPresent()) {
      secondLevelCacheHitBytes.addSample(outputFileFetchResult.artifactSizeBytes().get());
    }

    return fetchResult;
  }

  @Override
  public ListenableFuture<Void> store(
      final ImmutableSet<RuleKey> ruleKeys,
      final ImmutableMap<String, String> metadata,
      final BorrowablePath output) {

    return Futures.transformAsync(
        attemptTwoLevelStore(ruleKeys, metadata, output),
        new AsyncFunction<Boolean, Void>() {
          @Override
          public ListenableFuture<Void> apply(Boolean input) throws Exception {
            if (input) {
              return Futures.immediateFuture(null);
            }
            return delegate.store(ruleKeys, metadata, output);
          }
        });
  }

  @VisibleForTesting
  ArtifactCache getDelegate() {
    return delegate;
  }

  private ListenableFuture<Boolean> attemptTwoLevelStore(
      final ImmutableSet<RuleKey> ruleKeys,
      final ImmutableMap<String, String> metadata,
      final BorrowablePath output) {

    ListenableFuture<Optional<String>> contentHash = Futures.transformAsync(
        Futures.<Void>immediateFuture(null),
        new AsyncFunction<Void, Optional<String>>() {
          @Override
          public ListenableFuture<Optional<String>> apply(Void input) throws Exception {
            long fileSize = projectFilesystem.getFileSize(output.getPath());

            if (!performTwoLevelStores ||
                fileSize < minimumTwoLevelStoredArtifactSize ||
                (maximumTwoLevelStoredArtifactSize.isPresent() &&
                    fileSize > maximumTwoLevelStoredArtifactSize.get())) {
              return Futures.immediateFuture(Optional.<String>absent());
            }

            long hashComputationStart = System.currentTimeMillis();
            String hashCode = projectFilesystem.computeSha1(output.getPath()) + "2c00";
            long hashComputationEnd = System.currentTimeMillis();
            secondLevelHashComputationTimeMs.addSample(hashComputationEnd - hashComputationStart);

            return Futures.transform(
                delegate.store(
                    ImmutableSet.of(new RuleKey(hashCode)),
                    ImmutableMap.<String, String>of(),
                    output),
                Functions.constant(Optional.of(hashCode)));
          }
        },
        listeningExecutorService
    );

    return Futures.transformAsync(
        contentHash,
        new AsyncFunction<Optional<String>, Boolean>() {
          @Override
          public ListenableFuture<Boolean> apply(Optional<String> contentHash) throws Exception {
            if (!contentHash.isPresent()) {
              return Futures.immediateFuture(false);
            }

            ImmutableMap<String, String> metadataWithCacheKey =
                ImmutableMap.<String, String>builder()
                    .putAll(metadata)
                    .put(METADATA_KEY, contentHash.get())
                    .build();

            return Futures.transform(
                delegate.store(
                    ruleKeys,
                    metadataWithCacheKey,
                    BorrowablePath.notBorrowablePath(emptyFilePath)),
                Functions.constant(true));
          }
        },
        listeningExecutorService
    );
  }

  @Override
  public boolean isStoreSupported() {
    return delegate.isStoreSupported();
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
