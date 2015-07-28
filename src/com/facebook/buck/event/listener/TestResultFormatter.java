/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;

import java.util.List;

public class TestResultFormatter {

  private final Ansi ansi;
  private final Verbosity verbosity;
  private TestResultSummaryVerbosity summaryVerbosity;

  public enum FormatMode {
      BEFORE_TEST_RUN,
      AFTER_TEST_RUN
  }

  public TestResultFormatter(
      Ansi ansi,
      Verbosity verbosity,
      TestResultSummaryVerbosity summaryVerbosity) {
    this.ansi = ansi;
    this.verbosity = verbosity;
    this.summaryVerbosity = summaryVerbosity;
  }

  public void runStarted(
      ImmutableList.Builder<String> addTo,
      boolean isRunAllTests,
      TestSelectorList testSelectorList,
      boolean shouldExplainTestSelectorList,
      ImmutableSet<String> targetNames,
      FormatMode formatMode) {
    String prefix;
    if (formatMode == FormatMode.BEFORE_TEST_RUN) {
      prefix = "TESTING";
    } else {
      prefix = "RESULTS FOR";
    }
    if (!testSelectorList.isEmpty()) {
      addTo.add(prefix + " SELECTED TESTS");
      if (shouldExplainTestSelectorList) {
        addTo.addAll(testSelectorList.getExplanation());
      }
    } else if (isRunAllTests) {
      addTo.add(prefix + " ALL TESTS");
    } else {
      addTo.add(prefix + " " + Joiner.on(' ').join(targetNames));
    }
  }

  /** Writes a detailed summary that ends with a trailing newline. */
  public void reportResult(ImmutableList.Builder<String> addTo, TestResults results) {
    if (
        verbosity.shouldPrintBinaryRunInformation() &&
            results.getTotalNumberOfTests() > 1) {
      addTo.add("");
      addTo.add(String.format(
              "Results for %s (%d/%d) %s",
              results.getBuildTarget().getFullyQualifiedName(),
              results.getSequenceNumber(),
              results.getTotalNumberOfTests(), verbosity));
    }
    for (TestCaseSummary testCase : results.getTestCases()) {
      addTo.add(testCase.getOneLineSummary(results.getDependenciesPassTheirTests(), ansi));

      // Don't print the full error if there were no failures (so only successes and assumption
      // violations)
      if (testCase.isSuccess()) {
        continue;
      }

      for (TestResultSummary testResult : testCase.getTestResults()) {
        if (!results.getDependenciesPassTheirTests()) {
          continue;
        }

        // Report on either explicit failure
        if (!testResult.isSuccess()) {
          reportResultSummary(addTo, testResult);
        }
      }
    }
  }

  public void reportResultSummary(ImmutableList.Builder<String> addTo,
                                  TestResultSummary testResult) {
    addTo.add(String.format("%s %s %s: %s",
        testResult.getType().toString(),
        testResult.getTestCaseName(),
        testResult.getTestName(),
        testResult.getMessage()));

    if (testResult.getStacktrace() != null) {
      for (String line : Splitter.on("\n").split(testResult.getStacktrace())) {
        if (line.contains(testResult.getTestCaseName())) {
          addTo.add(ansi.asErrorText(line));
        } else {
          addTo.add(line);
        }
      }
    }

    if (summaryVerbosity.getIncludeStdOut() && testResult.getStdOut() != null) {
      addTo.add("====STANDARD OUT====", testResult.getStdOut());
    }

    if (summaryVerbosity.getIncludeStdErr() && testResult.getStdErr() != null) {
      addTo.add("====STANDARD ERR====", testResult.getStdErr());
    }
  }

  public void runComplete(ImmutableList.Builder<String> addTo, List<TestResults> completedResults) {
    // Print whether each test succeeded or failed.
    boolean isAllTestsPassed = true;
    boolean isAnyAssumptionViolated = false;
    ListMultimap<TestResults, TestCaseSummary> failingTests = ArrayListMultimap.create();

    int numFailures = 0;
    for (TestResults summary : completedResults) {
      if (!summary.isSuccess()) {
        isAllTestsPassed = false;
        numFailures += summary.getFailureCount();
        failingTests.putAll(summary, summary.getFailures());
      }
      for (TestCaseSummary testCaseSummary : summary.getTestCases()) {
        if (testCaseSummary.hasAssumptionViolations()) {
          isAnyAssumptionViolated = true;
          break;
        }
      }
    }

    // Print the summary of the test results.
    if (completedResults.isEmpty()) {
      addTo.add(ansi.asHighlightedFailureText("NO TESTS RAN"));
    } else if (isAllTestsPassed) {
      if (isAnyAssumptionViolated) {
        addTo.add(ansi.asHighlightedWarningText("TESTS PASSED (with some assumption violations)"));
      } else {
        addTo.add(ansi.asHighlightedSuccessText("TESTS PASSED"));
      }
    } else {
      addTo.add(ansi.asHighlightedFailureText(
          String.format(
              "TESTS FAILED: %d %s", numFailures, numFailures == 1 ? "FAILURE" : "FAILURES")));
      for (TestResults results : failingTests.keySet()) {
        addTo.add("Failed target: " + results.getBuildTarget().getFullyQualifiedName());
        for (TestCaseSummary summary : failingTests.get(results)) {
          addTo.add(summary.toString());
        }
      }
    }
  }
}
