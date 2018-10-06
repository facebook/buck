/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactInfo;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.util.CloseableHolder;
import com.facebook.buck.util.NamedTemporaryFile;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Default implementation of {@link ArtifactCacheByBuildRule} used by Stampede. */
public class DistBuildArtifactCacheImpl implements ArtifactCacheByBuildRule {
  private static final Logger LOG = Logger.get(DistBuildArtifactCacheImpl.class);
  // TODO(shivanker): Make these configurable.
  private static final int MAX_RULEKEYS_IN_MULTI_CONTAINS_REQUEST = 5000;

  private final ActionGraphBuilder graphBuilder;
  private final BuckEventBus eventBus;
  private final ListeningExecutorService executorService;
  private final ArtifactCache remoteCache;
  private final Optional<ArtifactCache> localCache;
  private final ParallelRuleKeyCalculator<RuleKey> ruleKeyCalculator;
  private final Map<BuildRule, ListenableFuture<Boolean>> remoteCacheContainsFutures;
  private final Map<BuildRule, ListenableFuture<BuildRule>> localUploadFutures;

  public DistBuildArtifactCacheImpl(
      ActionGraphBuilder graphBuilder,
      ListeningExecutorService executorService,
      ArtifactCache remoteCache,
      BuckEventBus eventBus,
      ParallelRuleKeyCalculator<RuleKey> ruleKeyCalculator,
      Optional<ArtifactCache> localCacheToUploadFrom) {
    this.graphBuilder = graphBuilder;
    this.eventBus = eventBus;
    this.executorService = executorService;
    this.remoteCache = remoteCache;
    this.localCache = localCacheToUploadFrom;

    this.remoteCacheContainsFutures = new ConcurrentHashMap<>();
    this.localUploadFutures = new HashMap<>();

    this.ruleKeyCalculator = ruleKeyCalculator;
  }

  private ListenableFuture<Map<RuleKey, CacheResult>> multiContainsAsync(List<RuleKey> ruleKeys) {
    List<ListenableFuture<ImmutableMap<RuleKey, CacheResult>>> requestResultsFutures =
        new ArrayList<>(ruleKeys.size() / MAX_RULEKEYS_IN_MULTI_CONTAINS_REQUEST + 1);

    while (ruleKeys.size() > 0) {
      int numKeysInCurrentRequest =
          Math.min(MAX_RULEKEYS_IN_MULTI_CONTAINS_REQUEST, ruleKeys.size());
      LOG.verbose("Making multi-contains request for [%d] rulekeys.", numKeysInCurrentRequest);
      requestResultsFutures.add(
          remoteCache.multiContainsAsync(
              ImmutableSet.copyOf(ruleKeys.subList(0, numKeysInCurrentRequest))));
      ruleKeys = ruleKeys.subList(numKeysInCurrentRequest, ruleKeys.size());
    }

    return Futures.transform(
        Futures.allAsList(requestResultsFutures),
        (List<Map<RuleKey, CacheResult>> requestResults) -> {
          Map<RuleKey, CacheResult> allResults = new HashMap<>();
          for (Map<RuleKey, CacheResult> partResults : requestResults) {
            allResults.putAll(partResults);
          }
          return allResults;
        },
        MoreExecutors.directExecutor());
  }

  @Override
  public boolean isLocalCachePresent() {
    return localCache.isPresent();
  }

  @Override
  public boolean remoteContains(BuildRule buildRule) {
    if (!remoteCacheContainsFutures.containsKey(buildRule)) {
      prewarmRemoteContains(ImmutableSet.of(buildRule));
    }
    return Futures.getUnchecked(remoteCacheContainsFutures.get(buildRule));
  }

  @Override
  public boolean localContains(BuildRule rule) {
    return localCache
        .map(
            cache -> {
              try {
                RuleKey key = ruleKeyCalculator.calculate(eventBus, rule).get();
                return Objects.requireNonNull(
                        cache.multiContainsAsync(ImmutableSet.of(key)).get().get(key))
                    .getType()
                    .isSuccess();
              } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
              }
            })
        .orElse(false);
  }

  @Override
  public synchronized ListenableFuture<BuildRule> uploadFromLocal(BuildRule rule)
      throws IOException {
    Preconditions.checkState(localCache.isPresent());
    if (localUploadFutures.containsKey(rule)) {
      return localUploadFutures.get(rule);
    }

    ListenableFuture<RuleKey> asyncRuleKey = ruleKeyCalculator.calculate(eventBus, rule);

    NamedTemporaryFile releasedFile;
    ListenableFuture<CacheResult> asyncfetchResult;
    try (CloseableHolder<NamedTemporaryFile> tempFile =
        new CloseableHolder<>(
            new NamedTemporaryFile(MostFiles.sanitize(rule.getBuildTarget().getShortName()), ""))) {
      asyncfetchResult =
          Futures.transformAsync(
              asyncRuleKey,
              ruleKey ->
                  localCache
                      .get()
                      .fetchAsync(
                          rule.getBuildTarget(),
                          ruleKey,
                          LazyPath.ofInstance(tempFile.get().get())),
              // We should already have computed the rulekey before calling this method, so using
              // DirectExecutor here is fine.
              MoreExecutors.directExecutor());

      releasedFile = tempFile.release();
    }

    ListenableFuture<Void> uploadFuture =
        Futures.transformAsync(
            asyncfetchResult,
            fetchResult -> {
              if (!fetchResult.getType().isSuccess()) {
                LOG.error(
                    "Could not upload missing target [%s] from local cache.",
                    rule.getBuildTarget().getFullyQualifiedName());
                return Futures.immediateFuture(null);
              }

              return remoteCache.store(
                  ArtifactInfo.builder()
                      .setRuleKeys(ImmutableSet.of(Futures.getUnchecked(asyncRuleKey)))
                      .setMetadata(fetchResult.getMetadata())
                      .build(),
                  BorrowablePath.notBorrowablePath(releasedFile.get()));
            },
            executorService);

    ListenableFuture<BuildRule> finalFuture =
        Futures.transform(
            uploadFuture,
            result -> {
              try {
                releasedFile.close();
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
              return rule;
            },
            MoreExecutors.directExecutor());

    localUploadFutures.put(rule, finalFuture);
    return finalFuture;
  }

  @Override
  public void prewarmRemoteContainsForAllKnownRules() {
    prewarmRemoteContains(
        ruleKeyCalculator
            .getAllKnownTargets()
            .stream()
            .map(graphBuilder::requireRule)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Override
  public synchronized void prewarmRemoteContains(ImmutableSet<BuildRule> rulesToBeChecked) {
    @SuppressWarnings("PMD.PrematureDeclaration")
    Stopwatch stopwatch = Stopwatch.createStarted();
    Set<BuildRule> unseenRules =
        rulesToBeChecked
            .stream()
            .filter(rule -> !remoteCacheContainsFutures.containsKey(rule))
            .collect(Collectors.toSet());

    if (unseenRules.size() == 0) {
      return;
    }

    LOG.info("Checking remote cache for [%d] new rules.", unseenRules.size());
    Map<BuildRule, ListenableFuture<RuleKey>> rulesToKeys =
        Maps.asMap(unseenRules, rule -> ruleKeyCalculator.calculate(eventBus, rule));

    ListenableFuture<Map<RuleKey, CacheResult>> keysToCacheResultFuture =
        Futures.transformAsync(
            Futures.allAsList(rulesToKeys.values()),
            ruleKeys -> {
              LOG.info(
                  "Computing RuleKeys for %d new rules took %dms.",
                  unseenRules.size(), stopwatch.elapsed(TimeUnit.MILLISECONDS));
              stopwatch.reset();
              stopwatch.start();
              return multiContainsAsync(ruleKeys);
            },
            executorService);

    Map<BuildRule, ListenableFuture<Boolean>> containsResultsForUnseenRules =
        Maps.asMap(
            unseenRules,
            rule ->
                Futures.transform(
                    keysToCacheResultFuture,
                    keysToCacheResult ->
                        Objects.requireNonNull(
                                keysToCacheResult.get(Futures.getUnchecked(rulesToKeys.get(rule))))
                            .getType()
                            .isSuccess(),
                    MoreExecutors.directExecutor()));

    remoteCacheContainsFutures.putAll(containsResultsForUnseenRules);
    Futures.allAsList(containsResultsForUnseenRules.values())
        .addListener(
            () ->
                LOG.info(
                    "Checking the remote cache for %d rules took %dms.",
                    unseenRules.size(), stopwatch.elapsed(TimeUnit.MILLISECONDS)),
            MoreExecutors.directExecutor());
  }

  @Override
  public List<ListenableFuture<BuildRule>> getAllUploadRuleFutures() {
    return new ArrayList<>(localUploadFutures.values());
  }

  @Override
  @SuppressWarnings("CheckReturnValue")
  public void close() {
    Futures.transform(
        Futures.allAsList(remoteCacheContainsFutures.values()),
        remoteContainsResults -> {
          LOG.info(
              "Hit [%d out of %d] targets checked in the remote cache. "
                  + "[%d] targets were uploaded from the local cache.",
              remoteContainsResults.stream().filter(r -> r).count(),
              remoteContainsResults.size(),
              localUploadFutures.size());
          return null;
        },
        MoreExecutors.directExecutor());
  }
}
