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

package com.facebook.buck.rules;

import com.facebook.buck.util.Ansi;
import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableList;

import java.util.List;

import javax.annotation.concurrent.Immutable;

/**
 * Represents the test results from multiple test cases.
 */
@Immutable
public class TestResults {

  private static final TestResults EMPTY_TEST_RESULTS = new TestResults(
      ImmutableList.<TestCaseSummary>of());

  private final ImmutableList<TestCaseSummary> testCases;
  private final List<TestCaseSummary> failures;
  private final int failureCount;

  @Beta
  public TestResults(List<TestCaseSummary> testCases) {
    this.testCases = ImmutableList.copyOf(testCases);

    int failureCount = 0;
    ImmutableList.Builder<TestCaseSummary> failures = ImmutableList.builder();
    for (TestCaseSummary result : testCases) {
      if (!result.isSuccess()) {
        failures.add(result);
        failureCount += result.getFailureCount();
      }
    }
    this.failures = failures.build();
    this.failureCount = failureCount;
  }

  public static TestResults getEmptyTestResults() {
    return EMPTY_TEST_RESULTS;
  }

  public boolean isSuccess() {
    return failures.isEmpty();
  }

  public int getFailureCount() {
    return failureCount;
  }

  public ImmutableList<TestCaseSummary> getTestCases() {
    return testCases;
  }

  /** @return a detailed summary that ends with a trailing newline */
  public String getSummaryWithFailureDetails(Ansi ansi) {
    StringBuilder builder = new StringBuilder();
    for (TestCaseSummary testCase : testCases) {
      builder.append(testCase.getOneLineSummary(ansi)).append('\n');

      if (!testCase.isSuccess()) {
        for (TestResultSummary testResult : testCase.getTestResults()) {
          if (!testResult.isSuccess()) {
            builder.append(String.format("FAILURE %s: %s\n%s\n",
                testResult.getTestName(),
                testResult.getMessage(),
                testResult.getStacktrace()));

            if (testResult.getStdOut() != null) {
              builder.append(String.format("====STANDARD OUT====\n%s\n", testResult.getStdOut()));
            }

            if (testResult.getStdErr() != null) {
              builder.append(String.format("====STANDARD ERR====\n%s\n", testResult.getStdErr()));
            }
          }
        }
      }
    }
    return builder.toString();
  }

}

