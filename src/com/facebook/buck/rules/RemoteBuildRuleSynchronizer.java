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
package com.facebook.buck.rules;

import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.concurrent.GuardedBy;

/**
 * Used by distributed build client to synchronize the local build and remote build state, which
 * ensures that rules have finished building remotely before the local build fetches them from the
 * cache.
 */
public class RemoteBuildRuleSynchronizer
    implements RemoteBuildRuleCompletionWaiter, RemoteBuildRuleCompletionNotifier {
  private static final Logger LOG = Logger.get(RemoteBuildRuleSynchronizer.class);

  private final Map<String, SettableFuture<Void>> resultFuturesByBuildTarget = new HashMap<>();
  private final Set<String> completedRules = new HashSet<>();
  private final Set<String> startedRules = new HashSet<>();
  private boolean remoteBuildFinished = false;
  private final SettableFuture<Void> mostBuildRulesFinished = SettableFuture.create();

  @GuardedBy("this")
  private boolean alwaysWaitForRemoteBuildBeforeProceedingLocally = false;

  public RemoteBuildRuleSynchronizer() {}

  @Override
  public synchronized ListenableFuture<Void> waitForBuildRuleToFinishRemotely(BuildRule buildRule) {
    String buildTarget = buildRule.getFullyQualifiedName();
    if (!buildRule.isCacheable() || remoteBuildFinished) {
      LOG.info(String.format("Returning immediate future for build target [%s]", buildTarget));
      // Stampede transfers artifacts via the cache. If the build rule isn't cacheable, then
      // proceed with next local steps immediately (i.e. cache fetches for all dependencies).
      return Futures.immediateFuture(null);
    }

    LOG.info(String.format("Returning future that waits for build target [%s]", buildTarget));
    return createCompletionFutureIfNotPresent(buildTarget);
  }

  @Override
  public ListenableFuture<Void> waitForMostBuildRulesToFinishRemotely() {
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
    createCompletionFutureIfNotPresent(buildTarget).set(null);
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

  /** When the remote build has finished (or failed), all rules should be unlocked. */
  @Override
  public synchronized void signalCompletionOfRemoteBuild() {
    LOG.info("Remote build is finished. Unlocking all rules");

    // Unlock all existing rules
    for (SettableFuture<Void> resultFuture : resultFuturesByBuildTarget.values()) {
      resultFuture.set(null);
    }

    // If for whatever reason the 'most build rules finished' event wasn't received, we can
    // be sure that at this point most build rules are finished, so unlock this Future too.
    signalMostBuildRulesFinished();

    // Set flag so that all future waitForBuildRuleToFinishRemotely calls return immediately.
    remoteBuildFinished = true;
  }

  @Override
  public void signalMostBuildRulesFinished() {
    if (mostBuildRulesFinished.isDone()) {
      return;
    }

    LOG.info("Most build rules finished.");
    mostBuildRulesFinished.set(null);
  }

  @VisibleForTesting
  protected synchronized boolean buildCompletionWaitingFutureCreatedForTarget(String buildTarget) {
    return resultFuturesByBuildTarget.containsKey(buildTarget);
  }

  private SettableFuture<Void> createCompletionFutureIfNotPresent(String buildTarget) {
    if (!resultFuturesByBuildTarget.containsKey(buildTarget)) {
      resultFuturesByBuildTarget.put(buildTarget, SettableFuture.create());
    }

    return Preconditions.checkNotNull(resultFuturesByBuildTarget.get(buildTarget));
  }

  public synchronized void switchToAlwaysWaitingMode() {
    alwaysWaitForRemoteBuildBeforeProceedingLocally = true;
  }
}
