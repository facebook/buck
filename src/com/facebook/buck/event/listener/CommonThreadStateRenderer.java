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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Preconditions;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;

public class CommonThreadStateRenderer {
  /** Amount of time a rule can run before we render it with as a warning. */
  private static final long WARNING_THRESHOLD_MS = 15000;

  /** Amount of time a rule can run before we render it with as an error. */
  private static final long ERROR_THRESHOLD_MS = 30000;

  /** Maximum width of the terminal. */
  private final int outputMaxColumns;

  private final Ansi ansi;
  private final Function<Long, String> formatTimeFunction;
  private final long currentTimeMs;
  private final ImmutableMap<Long, ThreadRenderingInformation> threadInformationMap;

  public CommonThreadStateRenderer(
      Ansi ansi,
      Function<Long, String> formatTimeFunction,
      long currentTimeMs,
      int outputMaxColumns,
      final ImmutableMap<Long, ThreadRenderingInformation> threadInformationMap) {
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
    String linePrefix = " - ";
    lineBuilder.append(linePrefix);
    if (!startEvent.isPresent() || !buildTarget.isPresent()) {
      lineBuilder.append("IDLE");
      return ansi.asSubtleText(lineBuilder.toString());
    } else {
      String buildTargetStr = buildTarget.get().toString();
      String ellipsis = "... ";
      String elapsedTimeStr = formatElapsedTime(elapsedTimeMs);
      if (linePrefix.length()
              + buildTargetStr.length()
              + ellipsis.length()
              + elapsedTimeStr.length()
          > outputMaxColumns) {
        buildTargetStr =
            buildTargetStr.substring(
                0,
                outputMaxColumns
                    - (linePrefix.length() + elapsedTimeStr.length() + ellipsis.length()));
      }
      lineBuilder.append(buildTargetStr);
      lineBuilder.append(ellipsis);
      lineBuilder.append(elapsedTimeStr);
      if (linePrefix.length()
              + buildTargetStr.length()
              + ellipsis.length()
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
      return ansi.asSubtleText("[ ]");
    } else {
      String animationFrames = ":':.";
      int offset = (int) ((currentTimeMs / 400) % animationFrames.length());
      String status = "[" + animationFrames.charAt(offset) + "]";
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
