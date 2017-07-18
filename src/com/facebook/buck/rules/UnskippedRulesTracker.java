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

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.model.BuildTarget;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps track of the number of build rules that either were already executed or might be executed
 * in the future. This class assumes that a rule that was not yet executed might be executed in the
 * future if either there is a dependency path from a top level rule to this rule such that no rule
 * on the path was yet executed or the rule is a runtime dependency of a rule fulfilling the
 * previous condition. This model is consistent with {@link CachingBuildEngine}'s shallow build
 * mode. The class uses reference counting to efficiently keep track of those rules.
 */
public class UnskippedRulesTracker {

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

  public UnskippedRulesTracker(RuleDepsCache ruleDepsCache, BuildRuleResolver ruleResolver) {
    this.ruleDepsCache = ruleDepsCache;
    this.ruleResolver = ruleResolver;
  }

  public void registerTopLevelRule(BuildRule rule, final BuckEventBus eventBus) {
    // Add a reference to the top-level rule so that it is never marked as skipped.
    acquireReference(rule);
    sendEventIfStateChanged(eventBus);
  }

  public void markRuleAsUsed(final BuildRule rule, final BuckEventBus eventBus) {
    // Add a reference to the used rule so that it is never marked as skipped.
    acquireReference(rule);
    if (rule instanceof HasRuntimeDeps) {
      // Add references to rule's runtime deps since they cannot be skipped now.
      ruleResolver
          .getAllRules(
              ((HasRuntimeDeps) rule).getRuntimeDeps(new SourcePathRuleFinder(ruleResolver))
                  ::iterator)
          .forEach(this::acquireReference);
    }

    // Release references from rule's dependencies since this rule will not need them anymore.
    ruleDepsCache.get(rule).forEach(this::releaseReference);
    sendEventIfStateChanged(eventBus);
  }

  private void sendEventIfStateChanged(BuckEventBus eventBus) {
    if (stateChanged.getAndSet(false)) {
      eventBus.post(BuildEvent.unskippedRuleCountUpdated(unskippedRules.get()));
    }
  }

  // TODO(cjhopman): convert these to be non-recursive.
  private void acquireReference(BuildRule rule) {
    AtomicInteger referenceCount = ruleReferenceCounts.getUnchecked(rule.getBuildTarget());
    int newValue = referenceCount.incrementAndGet();
    if (newValue == 1) {
      // 0 -> 1 transition means that the rule might be used in the future (not skipped for now).
      unskippedRules.incrementAndGet();
      stateChanged.set(true);
      // Add references to all dependencies of the rule.
      ruleDepsCache.get(rule).forEach(this::acquireReference);
    }
  }

  private void releaseReference(BuildRule rule) {
    AtomicInteger referenceCount = ruleReferenceCounts.getUnchecked(rule.getBuildTarget());
    int newValue = referenceCount.decrementAndGet();
    if (newValue == 0) {
      // 1 -> 0 transition means that the rule can be marked as skipped.
      unskippedRules.decrementAndGet();
      stateChanged.set(true);
      // Remove references from all dependencies of the rule.
      ruleDepsCache.get(rule).forEach(this::releaseReference);
    }
  }
}
