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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.List;

import javax.annotation.concurrent.Immutable;

@Immutable
public class TestCaseSummary {

  private final String testCaseName;
  private final ImmutableList<TestResultSummary> testResults;
  private final boolean isSuccess;
  private final int failureCount;
  private final long totalTime;

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
  public String getOneLineSummary(Ansi ansi) {
    String status = ansi.asHighlightedStatusText(isSuccess(), isSuccess() ? "PASS" : "FAIL");
    return String.format("%s %s %2d Passed  %2d Failed   %s",
        status,
        TimeFormat.formatForConsole(totalTime, ansi),
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
