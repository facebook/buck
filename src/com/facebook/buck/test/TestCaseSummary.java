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

import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.TimeFormat;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.List;

import javax.annotation.concurrent.Immutable;

@Immutable
public class TestCaseSummary {

  /**
   * Transformation to annotate TestCaseSummary marking them as being read from cached results
   */
  public static final Function<TestCaseSummary, TestCaseSummary> TO_CACHED_TRANSFORMATION =
      new Function<TestCaseSummary, TestCaseSummary>() {

        @Override
        public TestCaseSummary apply(TestCaseSummary summary) {
          return new TestCaseSummary(summary, /* isCached */ true);
        }
      };

  private final String testCaseName;
  private final ImmutableList<TestResultSummary> testResults;
  private final boolean isSuccess;
  private final int failureCount;
  private final long totalTime;
  private final boolean isCached;

  /**
   * Creates a TestCaseSummary which is assumed to be not read from cached results
   */
  public TestCaseSummary(String testCaseName, List<TestResultSummary> testResults) {
    this.testCaseName = Preconditions.checkNotNull(testCaseName);
    this.testResults = ImmutableList.copyOf(testResults);

    boolean isSuccess = true;
    int failureCount = 0;
    long totalTime = 0L;
    for (TestResultSummary result : testResults) {
      totalTime += result.getTime();
      if (!result.isSuccess()) {
        isSuccess = false;
        ++failureCount;
      }
    }
    this.isSuccess = isSuccess;
    this.failureCount = failureCount;
    this.totalTime = totalTime;
    this.isCached = false;
  }

  /** Creates a copy of {@code summary} with the specified value of {@code isCached}. */
  private TestCaseSummary(TestCaseSummary summary, boolean isCached) {
    Preconditions.checkNotNull(summary);
    this.testCaseName = summary.testCaseName;
    this.testResults = summary.testResults;
    this.isSuccess = summary.isSuccess;
    this.failureCount = summary.failureCount;
    this.totalTime = summary.totalTime;
    this.isCached = isCached;
  }

  public boolean isSuccess() {
    return isSuccess;
  }

  public String getTestCaseName() {
    return testCaseName;
  }

  /** @return the total time to run all of the tests in this test case, in milliseconds */
  public long getTotalTime() {
    return totalTime;
  }

  /** @return a one-line, printable summary */
  public String getOneLineSummary(boolean hasPassingDependencies, Ansi ansi) {
    String status;
    Ansi.SeverityLevel severityLevel;
    if (!isSuccess()) {
      if (hasPassingDependencies) {
        severityLevel = Ansi.SeverityLevel.ERROR;
        status = ansi.asHighlightedStatusText(severityLevel, "FAIL");
      } else {
        severityLevel = Ansi.SeverityLevel.WARNING;
        status = ansi.asHighlightedStatusText(severityLevel, "DROP");
      }
    } else {
      severityLevel = Ansi.SeverityLevel.OK;
      status = ansi.asHighlightedStatusText(severityLevel, "PASS");
    }

    return String.format("%s %s %2d Passed  %2d Failed   %s",
        status,
        !isCached ? TimeFormat.formatForConsole(totalTime, ansi)
                  : ansi.asHighlightedStatusText(severityLevel, "CACHED"),
        testResults.size() - failureCount,
        failureCount,
        testCaseName);
  }

  public ImmutableList<TestResultSummary> getTestResults() {
    return testResults;
  }

  public int getFailureCount() {
    return failureCount;
  }

  @Override
  public String toString() {
    return String.format("%s %s",
        isSuccess() ? "PASS" : "FAIL",
        testCaseName);
  }
}
