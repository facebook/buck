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

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.log.Logger;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;

public class TestResultFormatter {

  private static final int DEFAULT_MAX_LOG_LINES = 50;
  private static final Logger LOG = Logger.get(TestResultFormatter.class);

  private final Ansi ansi;
  private final Verbosity verbosity;
  private final TestResultSummaryVerbosity summaryVerbosity;
  private final Locale locale;
  private final Optional<Path> testLogsPath;
  private final TimeZone timeZone;

  public enum FormatMode {
    BEFORE_TEST_RUN,
    AFTER_TEST_RUN
  }

  public TestResultFormatter(
      Ansi ansi,
      Verbosity verbosity,
      TestResultSummaryVerbosity summaryVerbosity,
      Locale locale,
      Optional<Path> testLogsPath) {
    this(ansi, verbosity, summaryVerbosity, locale, testLogsPath, TimeZone.getDefault());
  }

  @VisibleForTesting
  TestResultFormatter(
      Ansi ansi,
      Verbosity verbosity,
      TestResultSummaryVerbosity summaryVerbosity,
      Locale locale,
      Optional<Path> testLogsPath,
      TimeZone timeZone) {
    this.ansi = ansi;
    this.verbosity = verbosity;
    this.summaryVerbosity = summaryVerbosity;
    this.locale = locale;
    this.testLogsPath = testLogsPath;
    this.timeZone = timeZone;
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
    if (verbosity.shouldPrintBinaryRunInformation() && results.getTotalNumberOfTests() > 1) {
      addTo.add("");
      addTo.add(
          String.format(
              locale,
              "Results for %s (%d/%d) %s",
              results.getBuildTarget().getFullyQualifiedName(),
              results.getSequenceNumber(),
              results.getTotalNumberOfTests(),
              verbosity));
    }

    boolean shouldReportLogSummaryAfterTests = false;

    for (TestCaseSummary testCase : results.getTestCases()) {
      // Only mention classes with tests.
      if (testCase.getPassedCount() == 0
          && testCase.getFailureCount() == 0
          && testCase.getSkippedCount() == 0) {
        continue;
      }

      String oneLineSummary =
          testCase.getOneLineSummary(locale, results.getDependenciesPassTheirTests(), ansi);
      addTo.add(oneLineSummary);

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
          shouldReportLogSummaryAfterTests = true;
          reportResultSummary(addTo, testResult);
        }
      }
    }

    if (shouldReportLogSummaryAfterTests && verbosity != Verbosity.SILENT) {
      for (Path testLogPath : results.getTestLogPaths()) {
        if (Files.exists(testLogPath)) {
          reportLogSummary(
              locale,
              addTo,
              testLogPath,
              summaryVerbosity.getMaxDebugLogLines().orElse(DEFAULT_MAX_LOG_LINES));
        }
      }
    }
  }

  private static void reportLogSummary(
      Locale locale, ImmutableList.Builder<String> addTo, Path logPath, int maxLogLines) {
    if (maxLogLines <= 0) {
      return;
    }
    try {
      List<String> logLines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
      if (logLines.isEmpty()) {
        return;
      }
      addTo.add("====TEST LOGS====");

      int logLinesStartIndex;
      if (logLines.size() > maxLogLines) {
        addTo.add(String.format(locale, "Last %d test log lines from %s:", maxLogLines, logPath));
        logLinesStartIndex = logLines.size() - maxLogLines;
      } else {
        addTo.add(String.format(locale, "Logs from %s:", logPath));
        logLinesStartIndex = 0;
      }
      addTo.addAll(logLines.subList(logLinesStartIndex, logLines.size()));
    } catch (IOException e) {
      LOG.error(e, "Could not read test logs from %s", logPath);
    }
  }

  public void reportResultSummary(
      ImmutableList.Builder<String> addTo, TestResultSummary testResult) {
    addTo.add(
        String.format(
            locale,
            "%s %s %s: %s",
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

    if (summaryVerbosity.getIncludeStdOut() && !Strings.isNullOrEmpty(testResult.getStdOut())) {
      addTo.add("====STANDARD OUT====", testResult.getStdOut());
    }

    if (summaryVerbosity.getIncludeStdErr() && !Strings.isNullOrEmpty(testResult.getStdErr())) {
      addTo.add("====STANDARD ERR====", testResult.getStdErr());
    }

    if (Strings.isNullOrEmpty(testResult.getMessage())
        && Strings.isNullOrEmpty(testResult.getStacktrace())
        && Strings.isNullOrEmpty(testResult.getStdOut())
        && Strings.isNullOrEmpty(testResult.getStdErr())) {
      addTo.add("Test did not produce any output.");
    }
  }

  public void runComplete(
      ImmutableList.Builder<String> addTo,
      List<TestResults> completedResults,
      List<TestStatusMessage> testStatusMessages) {
    // Print whether each test succeeded or failed.
    boolean isDryRun = false;
    boolean hasAssumptionViolations = false;
    int numTestsPassed = 0;
    int numTestsFailed = 0;
    int numTestsSkipped = 0;
    ListMultimap<TestResults, TestCaseSummary> failingTests = ArrayListMultimap.create();

    ImmutableList.Builder<Path> testLogPathsBuilder = ImmutableList.builder();

    for (TestResults summary : completedResults) {
      testLogPathsBuilder.addAll(summary.getTestLogPaths());
      // Get failures up-front to include class-level initialization failures
      if (summary.getFailureCount() > 0) {
        numTestsFailed += summary.getFailureCount();
        failingTests.putAll(summary, summary.getFailures());
      }
      // Get passes/skips by iterating through each case
      for (TestCaseSummary testCaseSummary : summary.getTestCases()) {
        isDryRun = isDryRun || testCaseSummary.isDryRun();
        numTestsPassed += testCaseSummary.getPassedCount();
        numTestsSkipped += testCaseSummary.getSkippedCount();
        hasAssumptionViolations =
            hasAssumptionViolations || testCaseSummary.hasAssumptionViolations();
      }
    }
    // If no test runs to completion, don't fail, but warn
    if (numTestsPassed == 0 && numTestsFailed == 0) {
      String message;
      if (hasAssumptionViolations) {
        message = "NO TESTS RAN (assumption violations)";
      } else if (numTestsSkipped > 0) {
        message = "NO TESTS RAN (tests skipped)";
      } else {
        message = "NO TESTS RAN";
      }
      if (isDryRun) {
        addTo.add(ansi.asHighlightedSuccessText(message));
      } else {
        addTo.add(ansi.asHighlightedWarningText(message));
      }
      return;
    }

    // When all tests pass...
    if (numTestsFailed == 0) {
      ImmutableList<Path> testLogPaths = testLogPathsBuilder.build();
      if (testLogsPath.isPresent() && verbosity != Verbosity.SILENT) {
        try {
          if (MostFiles.concatenateFiles(testLogsPath.get(), testLogPaths)) {
            addTo.add("Updated test logs: " + testLogsPath.get());
          }
        } catch (IOException e) {
          LOG.warn(e, "Could not concatenate test logs %s to %s", testLogPaths, testLogsPath.get());
        }
      }
      if (hasAssumptionViolations) {
        addTo.add(ansi.asHighlightedWarningText("TESTS PASSED (with some assumption violations)"));
      } else {
        addTo.add(ansi.asHighlightedSuccessText("TESTS PASSED"));
      }
      return;
    }

    // When something fails...
    if (!testStatusMessages.isEmpty()) {
      addTo.add("====TEST STATUS MESSAGES====");
      SimpleDateFormat timestampFormat =
          new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]", Locale.US);
      timestampFormat.setTimeZone(timeZone);

      for (TestStatusMessage testStatusMessage : testStatusMessages) {
        addTo.add(
            String.format(
                locale,
                "%s[%s] %s",
                timestampFormat.format(new Date(testStatusMessage.getTimestampMillis())),
                testStatusMessage.getLevel(),
                testStatusMessage.getMessage()));
      }
    }

    addTo.add(
        ansi.asHighlightedFailureText(
            String.format(
                locale,
                "TESTS FAILED: %d %s",
                numTestsFailed,
                numTestsFailed == 1 ? "FAILURE" : "FAILURES")));
    for (TestResults results : failingTests.keySet()) {
      addTo.add("Failed target: " + results.getBuildTarget().getFullyQualifiedName());
      for (TestCaseSummary summary : failingTests.get(results)) {
        addTo.add(summary.toString());
      }
    }
  }
}
