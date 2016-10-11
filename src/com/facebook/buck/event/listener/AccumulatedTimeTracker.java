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
package com.facebook.buck.event.listener;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;


public class AccumulatedTimeTracker {

  private final ConcurrentMap<Long, Optional<? extends BuildRuleEvent>>
      threadsToRunningBuildRuleEvent;
  private final ConcurrentMap<Long, Optional<? extends TestRuleEvent>>
      threadsToRunningTestRuleEvent;
  // Time previously suspended runs of this rule.
  private final ConcurrentMap<BuildTarget, AtomicLong> accumulatedRuleTime;

  public AccumulatedTimeTracker(
      ExecutionEnvironment executionEnvironment
  ) {
    this.threadsToRunningBuildRuleEvent = new ConcurrentHashMap<>(
        executionEnvironment.getAvailableCores());
    this.threadsToRunningTestRuleEvent = new ConcurrentHashMap<>(
        executionEnvironment.getAvailableCores());
    this.accumulatedRuleTime = new ConcurrentHashMap<>();
  }

  @VisibleForTesting
  AccumulatedTimeTracker(
      Map<Long, Optional<? extends BuildRuleEvent>> threadsToRunningBuildRuleEvent,
      Map<Long, Optional<? extends TestRuleEvent>> threadsToRunningTestRuleEvent,
      Map<BuildTarget, AtomicLong> accumulatedRuleTime) {
    this.threadsToRunningBuildRuleEvent = new ConcurrentHashMap<>();
    this.threadsToRunningTestRuleEvent = new ConcurrentHashMap<>();
    this.accumulatedRuleTime = new ConcurrentHashMap<>();

    this.threadsToRunningBuildRuleEvent.putAll(threadsToRunningBuildRuleEvent);
    this.threadsToRunningTestRuleEvent.putAll(threadsToRunningTestRuleEvent);
    this.accumulatedRuleTime.putAll(accumulatedRuleTime);
  }

  public AtomicLong getTime(BuildTarget buildTarget) {
    return accumulatedRuleTime.get(buildTarget);
  }

  public ConcurrentMap<Long, Optional<? extends BuildRuleEvent>> getBuildEventsByThread() {
    return threadsToRunningBuildRuleEvent;
  }

  public ConcurrentMap<Long, Optional<? extends TestRuleEvent>> getTestEventsByThread() {
    return threadsToRunningTestRuleEvent;
  }

  public void didStartBuildRule(BuildRuleEvent.Started started) {
    threadsToRunningBuildRuleEvent.put(started.getThreadId(), Optional.of(started));
    accumulatedRuleTime.put(started.getBuildRule().getBuildTarget(), new AtomicLong(0));
  }

  public void didResumeBuildRule(BuildRuleEvent.Resumed resumed) {
    threadsToRunningBuildRuleEvent.put(resumed.getThreadId(), Optional.of(resumed));
  }

  public void didSuspendBuildRule(BuildRuleEvent.Suspended suspended) {
    updateAccumulatedBuildTime(suspended);
  }

  public void didFinishBuildRule(BuildRuleEvent.Finished finished) {
    updateAccumulatedBuildTime(finished);
  }

  public void didStartTestRule(TestRuleEvent.Started started) {
    threadsToRunningTestRuleEvent.put(started.getThreadId(), Optional.of(started));
    accumulatedRuleTime.put(started.getBuildTarget(), new AtomicLong(0));
  }

  public void didFinishTestRule(TestRuleEvent.Finished finished) {
    threadsToRunningTestRuleEvent.put(finished.getThreadId(), Optional.absent());
    accumulatedRuleTime.remove(finished.getBuildTarget());
  }

  private void updateAccumulatedBuildTime(BuildRuleEvent buildRuleEvent) {
    Optional<? extends BuildRuleEvent> started =
        Preconditions.checkNotNull(
            threadsToRunningBuildRuleEvent.put(
                buildRuleEvent.getThreadId(),
                Optional.absent()));
    Preconditions.checkState(started.isPresent());
    Preconditions.checkState(buildRuleEvent.getBuildRule().equals(started.get().getBuildRule()));
    AtomicLong current = accumulatedRuleTime.get(buildRuleEvent.getBuildRule().getBuildTarget());
    // It's technically possible that another thread receives resumed and finished events
    // while we're processing this one, so we have to check that the current counter exists.
    if (current != null) {
      current.getAndAdd(buildRuleEvent.getTimestamp() - started.get().getTimestamp());
    }
  }

}
