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
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import java.util.Comparator;
import java.util.Objects;
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
  private static final String STEP_INFO_PREFIX = " (running ";
  private static final String STEP_INFO_SUFFIX = ")";

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
                  Objects.requireNonNull(threadInformationMap.get(threadId1)).getElapsedTimeMs();
              long elapsedTime2 =
                  Objects.requireNonNull(threadInformationMap.get(threadId2)).getElapsedTimeMs();
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
    if (!startEvent.isPresent() || !buildTarget.isPresent()) {
      return ansi.asSubtleText(
          formatWithTruncatable(outputMaxColumns, LINE_PREFIX, IDLE_STRING, "", ""));
    }
    String buildTargetStr = buildTarget.get().toString();
    String elapsedTimeStr = formatElapsedTime(elapsedTimeMs);

    String lineWithoutStep =
        formatWithTruncatable(
            outputMaxColumns, LINE_PREFIX, ELLIPSIS + elapsedTimeStr, buildTargetStr, "");

    lineBuilder.append(lineWithoutStep);
    if (lineWithoutStep.length()
        < outputMaxColumns
            - (STEP_INFO_PREFIX.length() + STEP_INFO_SUFFIX.length() + ELLIPSIS.length())) {
      if (runningStep.isPresent() && stepCategory.isPresent()) {
        String stepTimeString =
            String.format(
                "[%s]%s",
                formatElapsedTime(currentTimeMs - runningStep.get().getTimestampMillis()),
                STEP_INFO_SUFFIX);
        lineBuilder.append(
            formatWithTruncatable(
                outputMaxColumns - lineWithoutStep.length(),
                STEP_INFO_PREFIX,
                stepTimeString,
                stepCategory.get(),
                ELLIPSIS));
      } else if (placeholderStepInformation.isPresent()) {
        lineBuilder.append(
            formatWithTruncatable(
                outputMaxColumns - lineWithoutStep.length(),
                " (",
                ")",
                placeholderStepInformation.get(),
                ELLIPSIS));
      }
    }
    if (elapsedTimeMs > ERROR_THRESHOLD_MS) {
      return ansi.asErrorText(lineBuilder.toString());
    }
    if (elapsedTimeMs > WARNING_THRESHOLD_MS) {
      return ansi.asWarningText(lineBuilder.toString());
    }
    return lineBuilder.toString();
  }

  private static String formatWithTruncatable(
      int maxLength, String prefix, String suffix, String truncatable, String truncateToken) {
    int prefixAndSuffixLength = prefix.length() + suffix.length();
    if (prefixAndSuffixLength + truncateToken.length() > maxLength) {
      return "";
    }

    if (prefixAndSuffixLength + truncatable.length() > maxLength) {
      truncatable =
          String.format(
              "%s%s",
              truncatable.substring(0, maxLength - prefixAndSuffixLength - truncateToken.length()),
              truncateToken);
    }

    return String.format("%s%s%s", prefix, truncatable, suffix);
  }

  public String renderShortStatus(boolean isActive, boolean renderSubtle, long elapsedTimeMs) {
    if (!isActive) {
      return ansi.asSubtleText(THREAD_SHORT_IDLE_STATUS);
    }
    int offset = (int) ((currentTimeMs / 400) % THREAD_SHORT_STATUS_ANIMATION.length());
    String status =
        String.format(THREAD_SHORT_STATUS_FORMAT, THREAD_SHORT_STATUS_ANIMATION.charAt(offset));
    if (renderSubtle) {
      return ansi.asSubtleText(status);
    }
    if (elapsedTimeMs > ERROR_THRESHOLD_MS) {
      return ansi.asErrorText(status);
    }
    if (elapsedTimeMs > WARNING_THRESHOLD_MS) {
      return ansi.asWarningText(status);
    }
    return status;
  }

  private String formatElapsedTime(long elapsedTimeMs) {
    return formatTimeFunction.apply(elapsedTimeMs);
  }
}
