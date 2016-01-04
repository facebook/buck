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
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.TestStatusMessageEvent;
import com.facebook.buck.rules.TestSummaryEvent;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class BuildThreadStateRenderer implements ThreadStateRenderer {

  private final CommonThreadStateRenderer commonThreadStateRenderer;
  private final ImmutableMap<Long, ThreadRenderingInformation> threadInformationMap;

  public BuildThreadStateRenderer(
      Ansi ansi,
      Function<Long, String> formatTimeFunction,
      long currentTimeMs,
      Map<Long, Optional<? extends BuildRuleEvent>> buildEventsByThread,
      Map<Long, Optional<? extends LeafEvent>> runningStepsByThread,
      Map<BuildTarget, AtomicLong> accumulatedTimesByRule) {
    this.commonThreadStateRenderer = new CommonThreadStateRenderer(
        ansi,
        formatTimeFunction,
        currentTimeMs,
        buildEventsByThread.keySet());
    this.threadInformationMap = getThreadInformationMap(
        currentTimeMs,
        buildEventsByThread,
        runningStepsByThread,
        accumulatedTimesByRule);
  }

  private static ImmutableMap<Long, ThreadRenderingInformation> getThreadInformationMap(
      long currentTimeMs,
      Map<Long, Optional<? extends BuildRuleEvent>> buildEventsByThread,
      Map<Long, Optional<? extends LeafEvent>> runningStepsByThread,
      Map<BuildTarget, AtomicLong> accumulatedTimesByRule) {
    ImmutableMap.Builder<Long, ThreadRenderingInformation> threadInformationMapBuilder =
        ImmutableMap.builder();
    ImmutableList<Long> threadIds = ImmutableList.copyOf(buildEventsByThread.keySet());
    for (long threadId : threadIds) {
      Optional<? extends BuildRuleEvent> buildRuleEvent = buildEventsByThread.get(threadId);
      if (buildRuleEvent == null) {
        continue;
      }
      Optional<BuildTarget> buildTarget = Optional.absent();
      if (buildRuleEvent.isPresent()) {
        buildTarget = Optional.of(buildRuleEvent.get().getBuildRule().getBuildTarget());
      }
      AtomicLong accumulatedTime = null;
      if (buildTarget.isPresent()) {
        accumulatedTime = accumulatedTimesByRule.get(buildTarget.get());
      }
      long elapsedTimeMs = 0;
      if (buildRuleEvent.isPresent() && accumulatedTime != null) {
        elapsedTimeMs = currentTimeMs - buildRuleEvent.get().getTimestamp() + accumulatedTime.get();
      } else {
        buildRuleEvent = Optional.absent();
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
              buildRuleEvent,
              Optional.<TestSummaryEvent>absent(),
              Optional.<TestStatusMessageEvent>absent(),
              runningStep,
              elapsedTimeMs));
    }
    return threadInformationMapBuilder.build();
  }

  @Override
  public ImmutableList<Long> getSortedThreadIds() {
    return commonThreadStateRenderer.getSortedThreadIds();
  }

  @Override
  public String renderStatusLine(long threadId, StringBuilder lineBuilder) {
    ThreadRenderingInformation threadInformation = Preconditions.checkNotNull(
        threadInformationMap.get(threadId));
    Optional<String> stepCategory = Optional.absent();
    if (threadInformation.getRunningStep().isPresent()) {
      stepCategory = Optional.of(threadInformation.getRunningStep().get().getCategory());
    }
    return commonThreadStateRenderer.renderLine(
        threadInformation.getBuildTarget(),
        threadInformation.getStartEvent(),
        threadInformation.getRunningStep(),
        stepCategory,
        Optional.of("checking local cache"),
        threadInformation.getElapsedTimeMs(),
        lineBuilder);
  }
}
