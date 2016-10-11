/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TestStatusMessageEvent;
import com.facebook.buck.rules.TestSummaryEvent;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class TestThreadStateRenderer implements ThreadStateRenderer {

  private static final Level MIN_LOG_LEVEL = Level.INFO;
  private final CommonThreadStateRenderer commonThreadStateRenderer;
  private final ImmutableMap<Long, ThreadRenderingInformation> threadInformationMap;

  public TestThreadStateRenderer(
      Ansi ansi,
      Function<Long, String> formatTimeFunction,
      long currentTimeMs,
      Map<Long, Optional<? extends TestSummaryEvent>> testSummariesByThread,
      Map<Long, Optional<? extends TestStatusMessageEvent>> testStatusMessagesByThread,
      Map<Long, Optional<? extends LeafEvent>> runningStepsByThread,
      AccumulatedTimeTracker accumulatedTimeTracker) {
    this.threadInformationMap = getThreadInformationMap(
        currentTimeMs,
        testSummariesByThread,
        testStatusMessagesByThread,
        runningStepsByThread,
        accumulatedTimeTracker);
    this.commonThreadStateRenderer = new CommonThreadStateRenderer(
        ansi,
        formatTimeFunction,
        currentTimeMs,
        threadInformationMap);
  }

  private static ImmutableMap<Long, ThreadRenderingInformation> getThreadInformationMap(
      long currentTimeMs,
      Map<Long, Optional<? extends TestSummaryEvent>> testSummariesByThread,
      Map<Long, Optional<? extends TestStatusMessageEvent>> testStatusMessagesByThread,
      Map<Long, Optional<? extends LeafEvent>> runningStepsByThread,
      AccumulatedTimeTracker accumulatedTimeTracker) {
    ImmutableMap.Builder<Long, ThreadRenderingInformation> threadInformationMapBuilder =
        ImmutableMap.builder();
    Map<Long, Optional<? extends TestRuleEvent>> testEventsByThread =
        accumulatedTimeTracker.getTestEventsByThread();
    ImmutableList<Long> threadIds = ImmutableList.copyOf(testEventsByThread.keySet());
    for (long threadId : threadIds) {
      Optional<? extends TestRuleEvent> testRuleEvent = testEventsByThread.get(threadId);
      if (testRuleEvent == null) {
        continue;
      }
      Optional<BuildTarget> buildTarget = Optional.absent();
      if (testRuleEvent.isPresent()) {
        buildTarget = Optional.of(testRuleEvent.get().getBuildTarget());
      }
      Optional<? extends TestSummaryEvent> testSummary = testSummariesByThread.get(threadId);
      if (testSummary == null) {
        testSummary = Optional.absent();
      }
      Optional<? extends TestStatusMessageEvent> testStatusMessage = testStatusMessagesByThread.get(
          threadId);
      if (testStatusMessage == null) {
        testStatusMessage = Optional.absent();
      }
      AtomicLong accumulatedTime = null;
      if (buildTarget.isPresent()) {
        accumulatedTime = accumulatedTimeTracker.getTime(buildTarget.get());
      }
      long elapsedTimeMs = 0;
      if (testRuleEvent.isPresent() && accumulatedTime != null) {
        elapsedTimeMs = currentTimeMs - testRuleEvent.get().getTimestamp() + accumulatedTime.get();
      } else {
        testRuleEvent = Optional.absent();
        buildTarget = Optional.absent();
      }
      Optional<? extends LeafEvent> runningStep = runningStepsByThread.get(threadId);
      if (runningStep == null) {
        runningStep = Optional.absent();
      }

      threadInformationMapBuilder.put(
          threadId,
          new ThreadRenderingInformation(
              buildTarget,
              testRuleEvent,
              testSummary,
              testStatusMessage,
              runningStep,
              elapsedTimeMs));
    }
    return threadInformationMapBuilder.build();
  }

  @Override
  public int getThreadCount() {
    return commonThreadStateRenderer.getThreadCount();
  }

  @Override
  public ImmutableList<Long> getSortedThreadIds(boolean sortByTime) {
    return commonThreadStateRenderer.getSortedThreadIds(sortByTime);
  }

  @Override
  public String renderStatusLine(long threadId, StringBuilder lineBuilder) {
    ThreadRenderingInformation threadInformation = Preconditions.checkNotNull(
        threadInformationMap.get(threadId));
    Optional<String> stepCategory = Optional.absent();
    Optional<? extends LeafEvent> runningStep = Optional.absent();
    if (threadInformation.getTestStatusMessage().isPresent() &&
        threadInformation.getTestStatusMessage().get()
            .getTestStatusMessage().getLevel().intValue() >= MIN_LOG_LEVEL.intValue()) {
      stepCategory = Optional.of(
          threadInformation.getTestStatusMessage().get().getTestStatusMessage().getMessage());
      runningStep = threadInformation.getTestStatusMessage();
    } else if (threadInformation.getTestSummary().isPresent()) {
      stepCategory = Optional.of(threadInformation.getTestSummary().get().getTestName());
      runningStep = threadInformation.getTestSummary();
    } else if (threadInformation.getRunningStep().isPresent()) {
      stepCategory = Optional.of(threadInformation.getRunningStep().get().getCategory());
      runningStep = threadInformation.getRunningStep();
    }
    return commonThreadStateRenderer.renderLine(
        threadInformation.getBuildTarget(),
        threadInformation.getStartEvent(),
        runningStep,
        stepCategory,
        Optional.absent(),
        threadInformation.getElapsedTimeMs(),
        lineBuilder);
  }

  @Override
  public String renderShortStatus(long threadId) {
    ThreadRenderingInformation threadInformation = Preconditions.checkNotNull(
        threadInformationMap.get(threadId));
    return commonThreadStateRenderer.renderShortStatus(
        threadInformation.getBuildTarget().isPresent(),
        /* renderSubtle = */ false,
        threadInformation.getElapsedTimeMs());
  }
}
