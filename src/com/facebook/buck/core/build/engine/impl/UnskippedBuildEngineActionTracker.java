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

package com.facebook.buck.core.build.engine.impl;

import com.facebook.buck.core.build.action.BuildEngineAction;
import com.facebook.buck.core.build.engine.RuleDepsCache;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps track of the number of {@link BuildEngineAction} that either were already executed or might
 * be executed in the future. This class assumes that a {@link BuildEngineAction} that was not yet
 * executed might be executed in the future if either there is a dependency path from a top level
 * {@link BuildEngineAction} to this {@link BuildEngineAction} such that no rule on the path was yet
 * executed or the rule is a runtime dependency of a rule fulfilling the previous condition. This
 * model is consistent with {@link com.facebook.buck.core.build.engine.impl.CachingBuildEngine}'s
 * shallow build mode. The class uses reference counting to efficiently keep track of those {@link
 * BuildEngineAction}.
 */
public class UnskippedBuildEngineActionTracker {

  private static final CacheLoader<BuildTarget, AtomicInteger> DEFAULT_REFERENCE_COUNT_LOADER =
      new CacheLoader<BuildTarget, AtomicInteger>() {
        @Override
        public AtomicInteger load(BuildTarget ignored) {
          return new AtomicInteger(0);
        }
      };

  private final RuleDepsCache ruleDepsCache;
  private final BuildRuleResolver ruleResolver;
  private final AtomicInteger unskippedRules = new AtomicInteger(0);
  private final AtomicBoolean stateChanged = new AtomicBoolean(false);
  private final LoadingCache<BuildTarget, AtomicInteger> ruleReferenceCounts =
      CacheBuilder.newBuilder().build(DEFAULT_REFERENCE_COUNT_LOADER);

  public UnskippedBuildEngineActionTracker(
      RuleDepsCache ruleDepsCache, BuildRuleResolver ruleResolver) {
    this.ruleDepsCache = ruleDepsCache;
    this.ruleResolver = ruleResolver;
  }

  public void registerTopLevelRule(BuildEngineAction action, BuckEventBus eventBus) {
    // Add a reference to the top-level BuildEngineAction so that it is never marked as skipped.
    acquireReference(action);
    sendEventIfStateChanged(eventBus);
  }

  public void markRuleAsUsed(BuildEngineAction action, BuckEventBus eventBus) {
    // Add a reference to the used BuildEngineAction so that it is never marked as skipped.
    acquireReference(action);
    if (action instanceof HasRuntimeDeps) {
      // Add references to rule's runtime deps since they cannot be skipped now.
      ruleResolver
          .getAllRules(((HasRuntimeDeps) action).getRuntimeDeps(ruleResolver)::iterator)
          .forEach(this::acquireReference);
    }

    // Release references from BuildEngineAction's dependencies since this rule will not need them
    // anymore.
    ruleDepsCache.get(action).forEach(this::releaseReference);
    sendEventIfStateChanged(eventBus);
  }

  private void sendEventIfStateChanged(BuckEventBus eventBus) {
    if (stateChanged.getAndSet(false)) {
      eventBus.post(BuildEvent.unskippedRuleCountUpdated(unskippedRules.get()));
    }
  }

  // TODO(cjhopman): convert these to be non-recursive.
  private void acquireReference(BuildEngineAction rule) {
    AtomicInteger referenceCount = ruleReferenceCounts.getUnchecked(rule.getBuildTarget());
    int newValue = referenceCount.incrementAndGet();
    if (newValue == 1) {
      // 0 -> 1 transition means that the BuildEngineAction might be used in the future (not skipped
      // for now).
      unskippedRules.incrementAndGet();
      stateChanged.set(true);
      // Add references to all dependencies of the BuildEngineAction.
      ruleDepsCache.get(rule).forEach(this::acquireReference);
    }
  }

  private void releaseReference(BuildEngineAction action) {
    AtomicInteger referenceCount = ruleReferenceCounts.getUnchecked(action.getBuildTarget());
    int newValue = referenceCount.decrementAndGet();
    if (newValue == 0) {
      // 1 -> 0 transition means that the rule can be marked as skipped.
      unskippedRules.decrementAndGet();
      stateChanged.set(true);
      // Remove references from all dependencies of the BuildEngineAction.
      ruleDepsCache.get(action).forEach(this::releaseReference);
    }
  }
}
