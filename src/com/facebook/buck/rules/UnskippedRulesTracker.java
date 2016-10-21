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
import com.facebook.buck.util.concurrent.MoreFutures;
import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

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

  private static final Function<Object, Void> NULL_FUNCTION = ignored -> null;

  private final AsyncFunction<ImmutableSortedSet<BuildRule>, Void> acquireReferences =
      this::acquireReferences;

  private final AsyncFunction<ImmutableSortedSet<BuildRule>, Void> releaseReferences =
      this::releaseReferences;

  private final RuleDepsCache ruleDepsCache;
  private final ListeningExecutorService executor;
  private final AtomicInteger unskippedRules = new AtomicInteger(0);
  private final AtomicBoolean stateChanged = new AtomicBoolean(false);
  private final LoadingCache<BuildTarget, AtomicInteger> ruleReferenceCounts =
      CacheBuilder.newBuilder().build(DEFAULT_REFERENCE_COUNT_LOADER);

  public UnskippedRulesTracker(RuleDepsCache ruleDepsCache, ListeningExecutorService executor) {
    this.ruleDepsCache = ruleDepsCache;
    this.executor = executor;
  }

  public ListenableFuture<Void> registerTopLevelRule(
      BuildRule rule,
      final BuckEventBus eventBus) {
    // Add a reference to the top-level rule so that it is never marked as skipped.
    ListenableFuture<Void> future = acquireReference(rule);
    future.addListener(
        () -> sendEventIfStateChanged(eventBus),
        MoreExecutors.directExecutor());
    return future;
  }

  public ListenableFuture<Void> markRuleAsUsed(
      final BuildRule rule,
      final BuckEventBus eventBus) {
    // Add a reference to the used rule so that it is never marked as skipped.
    ListenableFuture<Void> future = acquireReference(rule);

    if (rule instanceof HasRuntimeDeps) {
      // Add references to rule's runtime deps since they cannot be skipped now.
      future = MoreFutures.chainExceptions(
          future,
          acquireReferences(((HasRuntimeDeps) rule).getRuntimeDeps()));
    }

    // Release references from rule's dependencies since this rule will not need them anymore.
    future = Futures.transformAsync(
        future,
        input -> Futures.transformAsync(
            ruleDepsCache.get(rule),
            releaseReferences,
            executor));

    future.addListener(
        () -> sendEventIfStateChanged(eventBus),
        MoreExecutors.directExecutor());

    return future;
  }

  private void sendEventIfStateChanged(BuckEventBus eventBus) {
    if (stateChanged.getAndSet(false)) {
      eventBus.post(BuildEvent.unskippedRuleCountUpdated(unskippedRules.get()));
    }
  }

  private ListenableFuture<Void> acquireReference(BuildRule rule) {
    AtomicInteger referenceCount = ruleReferenceCounts.getUnchecked(rule.getBuildTarget());
    int newValue = referenceCount.incrementAndGet();
    if (newValue == 1) {
      // 0 -> 1 transition means that the rule might be used in the future (not skipped for now).
      unskippedRules.incrementAndGet();
      stateChanged.set(true);
      // Add references to all dependencies of the rule.
      return Futures.transformAsync(
          ruleDepsCache.get(rule),
          acquireReferences,
          executor);
    }
    return Futures.immediateFuture(null);
  }

  private ListenableFuture<Void> acquireReferences(ImmutableSortedSet<BuildRule> rules) {
    ImmutableList.Builder<ListenableFuture<Void>> futures = ImmutableList.builder();
    for (BuildRule rule : rules) {
      futures.add(acquireReference(rule));
    }
    return Futures.transform(
        Futures.allAsList(futures.build()),
        NULL_FUNCTION);
  }

  private ListenableFuture<Void> releaseReference(BuildRule rule) {
    AtomicInteger referenceCount = ruleReferenceCounts.getUnchecked(rule.getBuildTarget());
    int newValue = referenceCount.decrementAndGet();
    if (newValue == 0) {
      // 1 -> 0 transition means that the rule can be marked as skipped.
      unskippedRules.decrementAndGet();
      stateChanged.set(true);
      // Remove references from all dependencies of the rule.
      return Futures.transformAsync(
          ruleDepsCache.get(rule),
          releaseReferences,
          executor);
    }
    return Futures.immediateFuture(null);
  }

  private ListenableFuture<Void> releaseReferences(ImmutableSortedSet<BuildRule> rules) {
    ImmutableList.Builder<ListenableFuture<Void>> futures = ImmutableList.builder();
    for (BuildRule rule : rules) {
      futures.add(releaseReference(rule));
    }
    return Futures.transform(
        Futures.allAsList(futures.build()),
        NULL_FUNCTION);
  }
}
