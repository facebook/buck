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

import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BuildRuleThreadTracker {

  private final ConcurrentMap<Long, Optional<? extends BuildRuleEvent.BeginningBuildRuleEvent>>
      threadsToRunningBuildRuleEvent;
  private final ConcurrentMap<Long, Optional<? extends TestRuleEvent>>
      threadsToRunningTestRuleEvent;

  public BuildRuleThreadTracker(ExecutionEnvironment executionEnvironment) {
    this.threadsToRunningBuildRuleEvent =
        new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());
    this.threadsToRunningTestRuleEvent =
        new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());
  }

  @VisibleForTesting
  BuildRuleThreadTracker(
      Map<Long, Optional<? extends BuildRuleEvent.BeginningBuildRuleEvent>>
          threadsToRunningBuildRuleEvent,
      Map<Long, Optional<? extends TestRuleEvent>> threadsToRunningTestRuleEvent) {
    this.threadsToRunningBuildRuleEvent = new ConcurrentHashMap<>();
    this.threadsToRunningTestRuleEvent = new ConcurrentHashMap<>();

    this.threadsToRunningBuildRuleEvent.putAll(threadsToRunningBuildRuleEvent);
    this.threadsToRunningTestRuleEvent.putAll(threadsToRunningTestRuleEvent);
  }

  public void reset() {
    threadsToRunningBuildRuleEvent.clear();
    threadsToRunningTestRuleEvent.clear();
  }

  public ConcurrentMap<Long, Optional<? extends BuildRuleEvent.BeginningBuildRuleEvent>>
      getBuildEventsByThread() {
    return threadsToRunningBuildRuleEvent;
  }

  public ConcurrentMap<Long, Optional<? extends TestRuleEvent>> getTestEventsByThread() {
    return threadsToRunningTestRuleEvent;
  }

  public void didStartBuildRule(BuildRuleEvent.Started started) {
    handleBeginningEvent(started);
  }

  public void didResumeBuildRule(BuildRuleEvent.Resumed resumed) {
    handleBeginningEvent(resumed);
  }

  public void didSuspendBuildRule(BuildRuleEvent.Suspended suspended) {
    handleEndingEvent(suspended);
  }

  public void didFinishBuildRule(BuildRuleEvent.Finished finished) {
    handleEndingEvent(finished);
  }

  public void didStartTestRule(TestRuleEvent.Started started) {
    threadsToRunningTestRuleEvent.put(started.getThreadId(), Optional.of(started));
  }

  public void didFinishTestRule(TestRuleEvent.Finished finished) {
    threadsToRunningTestRuleEvent.put(finished.getThreadId(), Optional.empty());
  }

  private void handleBeginningEvent(BuildRuleEvent.BeginningBuildRuleEvent beginning) {
    threadsToRunningBuildRuleEvent.put(beginning.getThreadId(), Optional.of(beginning));
  }

  private void handleEndingEvent(BuildRuleEvent.EndingBuildRuleEvent ending) {
    Optional<? extends BuildRuleEvent> beginning =
        Objects.requireNonNull(
            threadsToRunningBuildRuleEvent.put(ending.getThreadId(), Optional.empty()));
    Preconditions.checkState(beginning.isPresent());
    Preconditions.checkState(ending.getBuildRule().equals(beginning.get().getBuildRule()));
  }
}
