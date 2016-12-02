/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.test;

import com.facebook.buck.event.external.elements.TestCaseSummaryExternalInterface;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.TimeFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Locale;

public class TestCaseSummary implements TestCaseSummaryExternalInterface<TestResultSummary> {

  /**
   * Transformation to annotate TestCaseSummary marking them as being read from cached results
   */
  public static final Function<TestCaseSummary, TestCaseSummary> TO_CACHED_TRANSFORMATION =
      summary -> new TestCaseSummary(summary, /* isCached */ true);
  public static final int MAX_STATUS_WIDTH = 7;

  private final String testCaseName;
  private final ImmutableList<TestResultSummary> testResults;
  private final boolean isDryRun;
  private final boolean hasAssumptionViolations;
  private final int skippedCount;
  private final int passCount;
  private final int failureCount;
  private final long totalTime;
  private final boolean isCached;

  /**
   * Creates a TestCaseSummary which is assumed to be not read from cached results
   */
  public TestCaseSummary(String testCaseName, List<TestResultSummary> testResults) {
    this.testCaseName = testCaseName;
    this.testResults = ImmutableList.copyOf(testResults);

    boolean isDryRun = false;
    boolean hasAssumptionViolations = false;
    int skippedCount = 0;
    int failureCount = 0;
    int passCount = 0;
    long totalTime = 0L;
    for (TestResultSummary result : testResults) {
      totalTime += result.getTime();
      switch (result.getType()) {
        case SUCCESS:
          ++passCount;
          break;

        case DRY_RUN:
          isDryRun = true;
          ++passCount;  // "pass" in the sense that it confirms the class can be loaded
          break;

        case DISABLED:
          ++skippedCount;
          break;

        case EXCLUDED:
          break;

        case ASSUMPTION_VIOLATION:
          hasAssumptionViolations = true;
          ++skippedCount;
          break;

        case FAILURE:
          ++failureCount;
          break;
      }
    }
    this.isDryRun = isDryRun;
    this.hasAssumptionViolations = hasAssumptionViolations;
    this.skippedCount = skippedCount;
    this.failureCount = failureCount;
    this.passCount = passCount;
    this.totalTime = totalTime;
    this.isCached = false;
  }

  /** Creates a copy of {@code summary} with the specified value of {@code isCached}. */
  private TestCaseSummary(TestCaseSummary summary, boolean isCached) {
    this.testCaseName = summary.testCaseName;
    this.testResults = summary.testResults;
    this.isDryRun = summary.isDryRun;
    this.hasAssumptionViolations = summary.hasAssumptionViolations;
    this.skippedCount = summary.skippedCount;
    this.passCount = summary.passCount;
    this.failureCount = summary.failureCount;
    this.totalTime = summary.totalTime;
    this.isCached = isCached;
  }

  @JsonIgnore
  public boolean isDryRun() {
    return isDryRun;
  }

  @Override
  public boolean isSuccess() {
    return failureCount == 0;
  }

  @Override
  public boolean hasAssumptionViolations() {
    return hasAssumptionViolations;
  }

  @Override
  public String getTestCaseName() {
    return testCaseName;
  }

  /** @return the total time to run all of the tests in this test case, in milliseconds */
  @Override
  public long getTotalTime() {
    return totalTime;
  }

  /** @return a one-line, printable summary */
  public String getOneLineSummary(Locale locale, boolean hasPassingDependencies, Ansi ansi) {
    String statusText;
    Ansi.SeverityLevel severityLevel;
    if (isDryRun) {
      severityLevel = Ansi.SeverityLevel.WARNING;
      statusText = "DRYRUN";
    } else if (!isSuccess()) {
      if (hasPassingDependencies) {
        severityLevel = Ansi.SeverityLevel.ERROR;
        statusText = "FAIL";
      } else {
        severityLevel = Ansi.SeverityLevel.WARNING;
        statusText = "DROP";
      }
    } else {
      if (hasAssumptionViolations) {
        severityLevel = Ansi.SeverityLevel.WARNING;
        statusText = "ASSUME";
      } else {
        if (passCount == 0) {
          severityLevel = Ansi.SeverityLevel.WARNING;
          statusText = "NOTESTS";
        } else {
          severityLevel = Ansi.SeverityLevel.OK;
          statusText = "PASS";
        }
      }
    }

    String status = ansi.asHighlightedStatusText(severityLevel, statusText);
    int paddingWidth = MAX_STATUS_WIDTH - statusText.length();
    String padding = "";
    for (int position = 0; position < paddingWidth; position++) {
      padding += ' ';
    }

    return String.format(
        locale,
        "%s%s %s %2d Passed  %2d Skipped  %2d Failed   %s",
        status,
        padding,
        !isCached ? TimeFormat.formatForConsole(locale, totalTime, ansi)
                  : ansi.asHighlightedStatusText(severityLevel, "CACHED"),
        getPassedCount(),
        skippedCount,
        failureCount,
        testCaseName);
  }

  @Override
  public ImmutableList<TestResultSummary> getTestResults() {
    return testResults;
  }

  @JsonIgnore
  public int getPassedCount() {
    return passCount;
  }

  @Override
  public int getSkippedCount() {
    return skippedCount;
  }

  @Override
  public int getFailureCount() {
    return failureCount;
  }

  @Override
  public String toString() {
    return String.format(
        Locale.US,
        "%s %s",
        getShortStatusSummaryString(),
        testCaseName);
  }

  private String getShortStatusSummaryString() {
    if (isDryRun) {
      return "DRYRUN";
    }
    if (failureCount > 0) {
      return "FAIL";
    }
    if (passCount > 0) {
      return "PASS";
    }
    if (skippedCount > 0) {
      if (hasAssumptionViolations) {
        return "ASSUME";
      } else {
        return "SKIPPED";
      }
    }
    return "NOTESTS";
  }
}
