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
package com.facebook.buck.core.build.distributed.synchronization.impl;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionNotifier;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.concurrent.GuardedBy;

/**
 * Used by distributed build client to synchronize the local build and remote build state, which
 * ensures that rules have finished building remotely before the local build fetches them from the
 * cache.
 */
public class RemoteBuildRuleSynchronizer
    implements RemoteBuildRuleCompletionWaiter, RemoteBuildRuleCompletionNotifier, AutoCloseable {
  private static final Logger LOG = Logger.get(RemoteBuildRuleSynchronizer.class);

  private final Map<String, SettableFuture<Void>> completionFuturesByBuildTarget = new HashMap<>();
  private final Set<String> completedRules = new HashSet<>();
  private final Set<String> startedRules = new HashSet<>();
  private final Set<String> unlockedRules = new HashSet<>();
  private boolean remoteBuildFinished = false;
  private final SettableFuture<Boolean> mostBuildRulesFinished = SettableFuture.create();

  private final Set<TimestampedBuildRuleCacheSyncFuture> backedOffBuildRulesWaitingForCacheSync =
      new HashSet<>();
  private final Map<String, Long> completionTimestampsByBuildTarget = new HashMap<>();
  private long remoteBuildFinishedTimestamp;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final ScheduledFuture<?> syncedBuildRulesChecker;
  private final long[] backOffsMillis;
  private final long cacheSyncMaxTotalBackoffMillis;
  private boolean cancelAllCacheSyncBackOffs = false;

  @GuardedBy("this")
  private boolean alwaysWaitForRemoteBuildBeforeProceedingLocally = false;

  public RemoteBuildRuleSynchronizer(
      Clock clock,
      ScheduledExecutorService scheduler,
      long cacheSyncFirstBackoffMillis,
      long cacheSyncMaxTotalBackoffMillis) {
    Preconditions.checkArgument(
        (cacheSyncFirstBackoffMillis == 0 && cacheSyncMaxTotalBackoffMillis == 0)
            || cacheSyncMaxTotalBackoffMillis >= cacheSyncFirstBackoffMillis
                && cacheSyncFirstBackoffMillis > 0);

    this.clock = clock;
    this.scheduler = scheduler;
    this.backOffsMillis =
        genBackOffsMillis(cacheSyncFirstBackoffMillis, cacheSyncMaxTotalBackoffMillis);
    this.cacheSyncMaxTotalBackoffMillis = cacheSyncMaxTotalBackoffMillis;
    this.syncedBuildRulesChecker =
        this.scheduler.scheduleAtFixedRate(
            this::triggerCacheChecksForBackedOffBuildRulesWithSyncedCache,
            0,
            cacheSyncFirstBackoffMillis,
            TimeUnit.MILLISECONDS);
  }

  private long[] genBackOffsMillis(
      long cacheSyncFirstBackoffMillis, long cacheSyncMaxTotalBackoffMillis) {
    // Backoffs disabled.
    if (cacheSyncFirstBackoffMillis == 0) {
      return new long[0];
    }

    ArrayList<Long> backOffs = new ArrayList<>();

    // Gen linear backoffs millis.
    long sumBackoffs = 0;
    long currentBackoff = cacheSyncFirstBackoffMillis;
    while (sumBackoffs + currentBackoff <= cacheSyncMaxTotalBackoffMillis) {
      backOffs.add(currentBackoff);
      sumBackoffs += currentBackoff;

      currentBackoff += cacheSyncFirstBackoffMillis;
    }
    if (sumBackoffs < cacheSyncMaxTotalBackoffMillis) {
      backOffs.add(cacheSyncMaxTotalBackoffMillis - sumBackoffs);
    }

    return Longs.toArray(backOffs);
  }

  private void triggerCacheChecksForBackedOffBuildRulesWithSyncedCache() {
    synchronized (this) {
      if (backedOffBuildRulesWaitingForCacheSync.size() == 0) {
        return;
      }
    }

    List<TimestampedBuildRuleCacheSyncFuture> buildRulesReadyForCacheCheck = Lists.newArrayList();
    synchronized (this) {
      long currentTimeMillis = clock.currentTimeMillis();
      for (TimestampedBuildRuleCacheSyncFuture buildRuleWaiting :
          backedOffBuildRulesWaitingForCacheSync) {
        if (buildRuleWaiting.timestampMillis < currentTimeMillis) {
          buildRulesReadyForCacheCheck.add(buildRuleWaiting);
        }
      }
      backedOffBuildRulesWaitingForCacheSync.removeAll(buildRulesReadyForCacheCheck);
    }

    for (TimestampedBuildRuleCacheSyncFuture buildRuleReady : buildRulesReadyForCacheCheck) {
      buildRuleReady.cacheSyncFuture.set(null);
    }
  }

  @Override
  public synchronized ListenableFuture<CacheResult> waitForBuildRuleToAppearInCache(
      BuildRule buildRule, Supplier<ListenableFuture<CacheResult>> cacheCheck) {
    String buildTarget = buildRule.getFullyQualifiedName();
    if (!buildRule.isCacheable()) {
      LOG.info(
          String.format("Doing only immediate cache check for build target [%s]", buildTarget));
      // Stampede transfers artifacts via the cache. If the build rule isn't cacheable, then
      // immediately do only the requested cache check (may contain logging etc.) without any
      // retries.
      // Will allow proceeding with next local steps immediately (i.e. cache fetches for
      // all dependencies).
      return cacheCheck.get();
    }

    // If build is already marked as finish, then cannot expect to get completion signal for rule
    // later (possible completion event was missed/misordered). Do not wait for it then.
    ListenableFuture<CacheResult> resultFuture =
        remoteBuildFinished
            ? cacheCheck.get()
            : Futures.transformAsync(
                createCompletionFutureIfNotPresent(buildTarget),
                (Void v) -> cacheCheck.get(),
                MoreExecutors.directExecutor());

    // Backoffs are disabled.
    if (cacheSyncMaxTotalBackoffMillis == 0) {
      return resultFuture;
    }

    for (int backOffNumber = 0; backOffNumber < backOffsMillis.length; backOffNumber++) {
      int backOffNumberForLambda = backOffNumber;
      resultFuture =
          Futures.transformAsync(
              resultFuture,
              result -> {
                // If we didn't get a miss (miss -> need more wait time), stop any further retries.
                if (result.getType() != CacheResultType.MISS) {
                  return Futures.immediateFuture(result);
                }
                return getNextCacheCheckResult(
                    result, cacheCheck, buildTarget, backOffNumberForLambda);
              },
              MoreExecutors.directExecutor());
    }

    LOG.info(String.format("Returning future that waits for build target [%s]", buildTarget));

    return resultFuture;
  }

  private synchronized ListenableFuture<CacheResult> getNextCacheCheckResult(
      CacheResult result,
      Supplier<ListenableFuture<CacheResult>> cacheCheck,
      String buildTarget,
      int backOffNumber) {
    if (cancelAllCacheSyncBackOffs || unlockedRules.contains(buildTarget)) {
      LOG.info(
          "Rule [%s] - cannot back off as rule unlocked or all backofffs cancelled.", buildTarget);
      return Futures.immediateFuture(result);
    }

    // Do not allow backoffs if completion happened longer than limit ago.
    long now = clock.currentTimeMillis();
    long completionTimestamp = completionTimestampsByBuildTarget.getOrDefault(buildTarget, now);
    // If entire build was completed and it was already signalled - use it for completion timestamp.
    completionTimestamp =
        remoteBuildFinished
            ? Math.min(completionTimestamp, remoteBuildFinishedTimestamp)
            : completionTimestamp;
    long maxBackoffTimestamp = completionTimestamp + cacheSyncMaxTotalBackoffMillis;
    if (maxBackoffTimestamp < now) {
      LOG.info(
          "Rule [%s] was completed %d millis ago - cannot back off (max total back off: %d millis).",
          buildTarget, now - completionTimestamp, cacheSyncMaxTotalBackoffMillis);
      return Futures.immediateFuture(result);
    }

    long backOffMillis = Math.min(backOffsMillis[backOffNumber], maxBackoffTimestamp - now);
    LOG.info(
        "Rule [%s], completed %d millis ago, not ready after %d attempts. Backing off %d millis.",
        buildTarget, now - completionTimestamp, backOffNumber + 1, backOffMillis);

    // Register timestamped settable future for the rule.
    SettableFuture<Void> backOffFuture = SettableFuture.create();
    backedOffBuildRulesWaitingForCacheSync.add(
        new TimestampedBuildRuleCacheSyncFuture(now + backOffMillis, backOffFuture));

    // Use 'scheduler' as executor instead of direct executor so that when
    // triggerCacheChecksForBackedOffBuildRulesWithSyncedCache() runs, it only does unlocking of
    // ready futures and doesn't execute any code of cacheCheck -> it will execute quickly.
    return Futures.transformAsync(backOffFuture, (Void v) -> cacheCheck.get(), scheduler);
  }

  @Override
  public ListenableFuture<Boolean> waitForMostBuildRulesToFinishRemotely() {
    return mostBuildRulesFinished;
  }

  @Override
  public synchronized void signalCompletionOfBuildRule(String buildTarget) {
    if (completedRules.contains(buildTarget)) {
      LOG.warn(
          String.format(
              "Attempted to signal build target [%s] that has already been signalled. Skipping..",
              buildTarget));
      return;
    }
    LOG.info(String.format("Signalling remote completion of build target [%s]", buildTarget));
    completedRules.add(buildTarget);
    completionTimestampsByBuildTarget.put(buildTarget, clock.currentTimeMillis());
    createCompletionFutureIfNotPresent(buildTarget).set(null);
  }

  @Override
  public synchronized void signalUnlockedBuildRule(String buildTarget) {
    if (unlockedRules.add(buildTarget)) {
      LOG.info("Unlocking build target [%s]. Client will not wait for cache sync.", buildTarget);
      signalCompletionOfBuildRule(buildTarget);
    } else {
      LOG.warn(
          String.format(
              "Attempted to signal build target [%s] that has already been signalled. Skipping..",
              buildTarget));
    }
  }

  @Override
  public synchronized void signalStartedRemoteBuildingOfBuildRule(String buildTarget) {
    LOG.info(String.format("Target [%s] has started building remotely", buildTarget));
    startedRules.add(buildTarget);
  }

  @Override
  public synchronized boolean shouldWaitForRemoteCompletionOfBuildRule(String buildTarget) {
    // If alwaysWaitForRemoteBuildBeforeProceedingLocally set, then CachingBuildEngine
    // should wait for remote build of rule before attempting to build locally,
    // even if it hasn't started building remotely yet.
    if (alwaysWaitForRemoteBuildBeforeProceedingLocally) {
      return true;
    }

    return startedRules.contains(buildTarget);
  }

  /**
   * When the remote build has finished (or failed), all rules should be marked as completed. If it
   * failed, we also do not want to do any more backoffs for rules waiting for cache sync.
   */
  @Override
  public synchronized void signalCompletionOfRemoteBuild(boolean success) {
    LOG.info("Remote build is finished. Signalling completion for all rules");

    // Signalling completion for all existing rules
    for (SettableFuture<Void> completionFuture : completionFuturesByBuildTarget.values()) {
      completionFuture.set(null);
    }

    // If for whatever reason the 'most build rules finished' event wasn't received, we can
    // be sure that at this point most build rules are finished, so unlock this Future too.
    signalMostBuildRulesFinished(success);

    // Set flag so that all future waitForBuildRuleToAppearInCache do not wait for completion
    // signal.
    remoteBuildFinished = true;
    remoteBuildFinishedTimestamp = clock.currentTimeMillis();

    // Only if build failed, then we want to stop all backoffs. Otherwise, they are needed for all
    // slowly syncing/large rules at end of build.
    if (!success) {
      cancelAllBackOffs();
    }
  }

  @Override
  public void signalMostBuildRulesFinished(boolean success) {
    if (mostBuildRulesFinished.isDone()) {
      return;
    }

    LOG.info("Most build rules finished.");
    mostBuildRulesFinished.set(success);
  }

  @Override
  public void close() {
    cancelAllBackOffs();
    syncedBuildRulesChecker.cancel(true);
  }

  private synchronized void cancelAllBackOffs() {
    if (cancelAllCacheSyncBackOffs) {
      return;
    }

    for (TimestampedBuildRuleCacheSyncFuture buildRuleWaiting :
        backedOffBuildRulesWaitingForCacheSync) {
      buildRuleWaiting.cacheSyncFuture.set(null);
    }
    cancelAllCacheSyncBackOffs = true;
  }

  @VisibleForTesting
  protected synchronized boolean buildCompletionWaitingFutureCreatedForTarget(String buildTarget) {
    return completionFuturesByBuildTarget.containsKey(buildTarget);
  }

  private SettableFuture<Void> createCompletionFutureIfNotPresent(String buildTarget) {
    if (!completionFuturesByBuildTarget.containsKey(buildTarget)) {
      completionFuturesByBuildTarget.put(buildTarget, SettableFuture.create());
    }

    return Preconditions.checkNotNull(completionFuturesByBuildTarget.get(buildTarget));
  }

  public synchronized void switchToAlwaysWaitingMode() {
    alwaysWaitForRemoteBuildBeforeProceedingLocally = true;
  }

  private class TimestampedBuildRuleCacheSyncFuture {
    private long timestampMillis;
    private SettableFuture<Void> cacheSyncFuture;

    public TimestampedBuildRuleCacheSyncFuture(
        long timestampMillis, SettableFuture<Void> cacheSyncFuture) {
      this.timestampMillis = timestampMillis;
      this.cacheSyncFuture = cacheSyncFuture;
    }
  }
}
