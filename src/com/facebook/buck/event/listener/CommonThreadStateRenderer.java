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
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.util.Ansi;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;

/** Renders the per thread information line during build phase on super console */
public class CommonThreadStateRenderer {
  /** Amount of time a rule can run before we render it with as a warning. */
  @VisibleForTesting static final long WARNING_THRESHOLD_MS = 15000;

  /** Amount of time a rule can run before we render it with as an error. */
  @VisibleForTesting static final long ERROR_THRESHOLD_MS = 30000;

  /** Amount of time for one animation frame of short thread status */
  @VisibleForTesting static final long ANIMATION_DURATION = 400;

  /** Maximum width of the terminal. */
  private final int outputMaxColumns;

  private static final String LINE_PREFIX = " - ";
  private static final String IDLE_STRING = "IDLE";
  private static final String ELLIPSIS = "... ";
  private static final String THREAD_SHORT_STATUS_FORMAT = "[%s]";
  private static final String THREAD_SHORT_IDLE_STATUS = "[ ]";
  private static final String THREAD_SHORT_STATUS_ANIMATION = ":':.";

  private final Ansi ansi;
  private final Function<Long, String> formatTimeFunction;
  private final long currentTimeMs;
  private final ImmutableMap<Long, ThreadRenderingInformation> threadInformationMap;

  public CommonThreadStateRenderer(
      Ansi ansi,
      Function<Long, String> formatTimeFunction,
      long currentTimeMs,
      int outputMaxColumns,
      ImmutableMap<Long, ThreadRenderingInformation> threadInformationMap) {
    this.ansi = ansi;
    this.formatTimeFunction = formatTimeFunction;
    this.currentTimeMs = currentTimeMs;
    this.threadInformationMap = threadInformationMap;
    this.outputMaxColumns = outputMaxColumns;
  }

  public int getThreadCount() {
    return threadInformationMap.size();
  }

  public ImmutableList<Long> getSortedThreadIds(boolean sortByTime) {
    Comparator<Long> comparator;
    if (sortByTime) {
      comparator =
          new Comparator<Long>() {
            private Comparator<Long> reverseOrdering = Ordering.natural().reverse();

            @Override
            public int compare(Long threadId1, Long threadId2) {
              long elapsedTime1 =
                  Preconditions.checkNotNull(threadInformationMap.get(threadId1))
                      .getElapsedTimeMs();
              long elapsedTime2 =
                  Preconditions.checkNotNull(threadInformationMap.get(threadId2))
                      .getElapsedTimeMs();
              return ComparisonChain.start()
                  .compare(elapsedTime1, elapsedTime2, reverseOrdering)
                  .compare(threadId1, threadId2)
                  .result();
            }
          };
    } else {
      comparator = Ordering.natural();
    }
    return FluentIterable.from(threadInformationMap.keySet()).toSortedList(comparator);
  }

  public String renderLine(
      Optional<BuildTarget> buildTarget,
      Optional<? extends AbstractBuckEvent> startEvent,
      Optional<? extends LeafEvent> runningStep,
      Optional<String> stepCategory,
      Optional<String> placeholderStepInformation,
      long elapsedTimeMs,
      StringBuilder lineBuilder) {
    lineBuilder.append(LINE_PREFIX);
    if (!startEvent.isPresent() || !buildTarget.isPresent()) {
      lineBuilder.append(IDLE_STRING);
      return ansi.asSubtleText(lineBuilder.toString());
    } else {
      String buildTargetStr = buildTarget.get().toString();
      String elapsedTimeStr = formatElapsedTime(elapsedTimeMs);
      if (LINE_PREFIX.length()
              + buildTargetStr.length()
              + ELLIPSIS.length()
              + elapsedTimeStr.length()
          > outputMaxColumns) {
        buildTargetStr =
            buildTargetStr.substring(
                0,
                outputMaxColumns
                    - (LINE_PREFIX.length() + elapsedTimeStr.length() + ELLIPSIS.length()));
      }
      lineBuilder.append(buildTargetStr);
      lineBuilder.append(ELLIPSIS);
      lineBuilder.append(elapsedTimeStr);
      if (LINE_PREFIX.length()
              + buildTargetStr.length()
              + ELLIPSIS.length()
              + elapsedTimeStr.length()
          >= outputMaxColumns) {
        return lineBuilder.toString();
      }

      if (runningStep.isPresent() && stepCategory.isPresent()) {
        lineBuilder.append(" (running ");
        lineBuilder.append(stepCategory.get());
        lineBuilder.append('[');
        lineBuilder.append(formatElapsedTime(currentTimeMs - runningStep.get().getTimestamp()));
        lineBuilder.append("])");

        if (elapsedTimeMs > ERROR_THRESHOLD_MS) {
          return ansi.asErrorText(lineBuilder.toString());
        } else if (elapsedTimeMs > WARNING_THRESHOLD_MS) {
          return ansi.asWarningText(lineBuilder.toString());
        } else {
          return lineBuilder.toString();
        }
      } else if (placeholderStepInformation.isPresent()) {
        lineBuilder.append(" (");
        lineBuilder.append(placeholderStepInformation.get());
        lineBuilder.append(')');
        return ansi.asSubtleText(lineBuilder.toString());
      } else {
        return lineBuilder.toString();
      }
    }
  }

  public String renderShortStatus(boolean isActive, boolean renderSubtle, long elapsedTimeMs) {
    if (!isActive) {
      return ansi.asSubtleText(THREAD_SHORT_IDLE_STATUS);
    } else {
      int offset = (int) ((currentTimeMs / 400) % THREAD_SHORT_STATUS_ANIMATION.length());
      String status =
          String.format(THREAD_SHORT_STATUS_FORMAT, THREAD_SHORT_STATUS_ANIMATION.charAt(offset));
      if (renderSubtle) {
        return ansi.asSubtleText(status);
      } else if (elapsedTimeMs > ERROR_THRESHOLD_MS) {
        return ansi.asErrorText(status);
      } else if (elapsedTimeMs > WARNING_THRESHOLD_MS) {
        return ansi.asWarningText(status);
      } else {
        return status;
      }
    }
  }

  private String formatElapsedTime(long elapsedTimeMs) {
    return formatTimeFunction.apply(elapsedTimeMs);
  }
}
