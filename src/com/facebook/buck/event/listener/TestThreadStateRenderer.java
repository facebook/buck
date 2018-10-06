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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.test.event.TestStatusMessageEvent;
import com.facebook.buck.core.test.event.TestSummaryEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.util.Ansi;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;

public class TestThreadStateRenderer implements MultiStateRenderer {

  private static final Level MIN_LOG_LEVEL = Level.INFO;
  private final CommonThreadStateRenderer commonThreadStateRenderer;
  private final ImmutableMap<Long, ThreadRenderingInformation> threadInformationMap;

  public TestThreadStateRenderer(
      Ansi ansi,
      Function<Long, String> formatTimeFunction,
      long currentTimeMs,
      int outputMaxColumns,
      Map<Long, Optional<? extends TestSummaryEvent>> testSummariesByThread,
      Map<Long, Optional<? extends TestStatusMessageEvent>> testStatusMessagesByThread,
      Map<Long, Optional<? extends LeafEvent>> runningStepsByThread,
      BuildRuleThreadTracker buildRuleThreadTracker) {
    this.threadInformationMap =
        getThreadInformationMap(
            currentTimeMs,
            testSummariesByThread,
            testStatusMessagesByThread,
            runningStepsByThread,
            buildRuleThreadTracker);
    this.commonThreadStateRenderer =
        new CommonThreadStateRenderer(
            ansi, formatTimeFunction, currentTimeMs, outputMaxColumns, threadInformationMap);
  }

  private static ImmutableMap<Long, ThreadRenderingInformation> getThreadInformationMap(
      long currentTimeMs,
      Map<Long, Optional<? extends TestSummaryEvent>> testSummariesByThread,
      Map<Long, Optional<? extends TestStatusMessageEvent>> testStatusMessagesByThread,
      Map<Long, Optional<? extends LeafEvent>> runningStepsByThread,
      BuildRuleThreadTracker buildRuleThreadTracker) {
    ImmutableMap.Builder<Long, ThreadRenderingInformation> threadInformationMapBuilder =
        ImmutableMap.builder();
    Map<Long, Optional<? extends TestRuleEvent>> testEventsByThread =
        buildRuleThreadTracker.getTestEventsByThread();
    ImmutableList<Long> threadIds = ImmutableList.copyOf(testEventsByThread.keySet());
    for (long threadId : threadIds) {
      Optional<? extends TestRuleEvent> testRuleEvent = testEventsByThread.get(threadId);
      if (testRuleEvent == null) {
        continue;
      }
      Optional<BuildTarget> buildTarget = Optional.empty();
      long elapsedTimeMs = 0;
      if (testRuleEvent.isPresent()) {
        buildTarget = Optional.of(testRuleEvent.get().getBuildTarget());
        elapsedTimeMs = currentTimeMs - testRuleEvent.get().getTimestamp();
      }
      threadInformationMapBuilder.put(
          threadId,
          new ThreadRenderingInformation(
              buildTarget,
              testRuleEvent,
              testSummariesByThread.getOrDefault(threadId, Optional.empty()),
              testStatusMessagesByThread.getOrDefault(threadId, Optional.empty()),
              runningStepsByThread.getOrDefault(threadId, Optional.empty()),
              elapsedTimeMs));
    }
    return threadInformationMapBuilder.build();
  }

  @Override
  public String getExecutorCollectionLabel() {
    return "THREADS";
  }

  @Override
  public int getExecutorCount() {
    return commonThreadStateRenderer.getThreadCount();
  }

  @Override
  public ImmutableList<Long> getSortedExecutorIds(boolean sortByTime) {
    return commonThreadStateRenderer.getSortedThreadIds(sortByTime);
  }

  @Override
  public String renderStatusLine(long threadID, StringBuilder lineBuilder) {
    ThreadRenderingInformation threadInformation =
        Objects.requireNonNull(threadInformationMap.get(threadID));
    Optional<String> stepCategory = Optional.empty();
    Optional<? extends LeafEvent> runningStep = Optional.empty();
    if (threadInformation.getTestStatusMessage().isPresent()
        && threadInformation
                .getTestStatusMessage()
                .get()
                .getTestStatusMessage()
                .getLevel()
                .intValue()
            >= MIN_LOG_LEVEL.intValue()) {
      stepCategory =
          Optional.of(
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
        Optional.empty(),
        threadInformation.getElapsedTimeMs(),
        lineBuilder);
  }

  @Override
  public String renderShortStatus(long threadId) {
    ThreadRenderingInformation threadInformation =
        Objects.requireNonNull(threadInformationMap.get(threadId));
    return commonThreadStateRenderer.renderShortStatus(
        threadInformation.getBuildTarget().isPresent(),
        /* renderSubtle = */ false,
        threadInformation.getElapsedTimeMs());
  }
}
