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

package com.facebook.buck.apple;

import com.facebook.buck.log.Logger;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to parse the output from {@code xctest} and {@code otest}.
 */
public class XctestOutputParsing {

  private static final Logger LOG = Logger.get(XctestOutputParsing.class);

  // Utility class; do not instantiate.
  private XctestOutputParsing() { }

  // XCTest output goes through NSLog, which adds some spew to the start.
  private static final String NSLOG_SPEW_PATTERN_STRING = "\\S+ \\S+ \\S+ ";

  private static final Pattern SUITE_STARTED_PATTERN = Pattern.compile(
      XctestOutputParsing.NSLOG_SPEW_PATTERN_STRING + "Test Suite '(\\S+)' started at (.*)");
  private static final Pattern SUITE_FINISHED_PATTERN = Pattern.compile(
      XctestOutputParsing.NSLOG_SPEW_PATTERN_STRING + "Test Suite '(\\S+)' finished at (.*).");
  private static final Pattern CASE_STARTED_PATTERN = Pattern.compile(
      XctestOutputParsing.NSLOG_SPEW_PATTERN_STRING + "Test Case '(.*)' started\\.?");
  private static final Pattern CASE_FINISHED_PATTERN = Pattern.compile(
      XctestOutputParsing.NSLOG_SPEW_PATTERN_STRING +
          "Test Case '(.*)' (passed|failed) \\((.*) seconds\\)\\.");
  private static final Pattern ERROR_PATTERN = Pattern.compile(
      XctestOutputParsing.NSLOG_SPEW_PATTERN_STRING + "(.*): error: (.*) : (.*)");
  private static final Pattern CASE_PATTERN = Pattern.compile("-\\[(\\S+) (\\S+)\\]");

  /**
   * Parses a stream of JSON objects as produced by {@code xctool -reporter json-stream}
   * and converts them into {@link TestCaseSummary} objects.
   */
  public static List<TestCaseSummary> parseOutputFromReader(Reader reader)
    throws IOException {
    ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder =
        ImmutableListMultimap.builder();

    try (BufferedReader br = new BufferedReader(reader)) {
      String line;
      while ((line = br.readLine()) != null) {
        handleLine(line, testResultSummariesBuilder);
      }
    }

    ImmutableList.Builder<TestCaseSummary> result = ImmutableList.builder();
    for (Map.Entry<String, Collection<TestResultSummary>> testCaseSummary :
             testResultSummariesBuilder.build().asMap().entrySet()) {
      result.add(
          new TestCaseSummary(
              testCaseSummary.getKey(),
              ImmutableList.copyOf(testCaseSummary.getValue())));
    }

    return result.build();
  }

  private static void handleLine(
      String line,
      ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder) {
    LOG.debug("Parsing xctest line: %s", line);

    Matcher suiteStartedMatcher = XctestOutputParsing.SUITE_STARTED_PATTERN.matcher(line);
    if (suiteStartedMatcher.find()) {
        handleSuiteStarted(suiteStartedMatcher, testResultSummariesBuilder);
        return;
    }

    Matcher suiteFinishedMatcher = XctestOutputParsing.SUITE_FINISHED_PATTERN.matcher(line);
    if (suiteFinishedMatcher.find()) {
        handleSuiteFinished(suiteFinishedMatcher, testResultSummariesBuilder);
        return;
    }

    Matcher caseStartedMatcher = XctestOutputParsing.CASE_STARTED_PATTERN.matcher(line);
    if (caseStartedMatcher.find()) {
        handleCaseStarted(caseStartedMatcher, testResultSummariesBuilder);
        return;
    }

    Matcher caseFinishedMatcher = XctestOutputParsing.CASE_FINISHED_PATTERN.matcher(line);
    if (caseFinishedMatcher.find()) {
        handleCaseFinished(caseFinishedMatcher, testResultSummariesBuilder);
        return;
    }

    Matcher errorMatcher = XctestOutputParsing.ERROR_PATTERN.matcher(line);
    if (errorMatcher.find()) {
        handleError(errorMatcher, testResultSummariesBuilder);
        return;
    }
  }

  public static void handleSuiteStarted(
      Matcher matcher,
      @SuppressWarnings("unused")
          ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder) {
    LOG.debug("Started suite: %s", matcher.group(1));
  }

  public static void handleSuiteFinished(
      Matcher matcher,
      @SuppressWarnings("unused")
          ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder) {
    LOG.debug("Finished suite: %s", matcher.group(1));
  }

  public static void handleCaseStarted(
      Matcher matcher,
      @SuppressWarnings("unused")
          ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder) {
    LOG.debug("Started case: %s", matcher.group(1));
  }

  public static void handleCaseFinished(
      Matcher matcher,
      ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder) {
    long timeMillis = 0;
    try {
      double duration = Double.parseDouble(matcher.group(3));
      timeMillis = (long) (duration * TimeUnit.SECONDS.toMillis(1));
    } catch (NumberFormatException e) {
      LOG.warn("Couldn't parse duration %s", matcher.group(3));
    }

    boolean succeeded = matcher.group(2).equals("passed");

    String group = null;
    String test = null;
    String name = matcher.group(1);

    Matcher nameMatcher = XctestOutputParsing.CASE_PATTERN.matcher(name);
    if (nameMatcher.find()) {
      group = nameMatcher.group(1);
      test = nameMatcher.group(2);
    } else {
      group = "Unknown";
      test = name;
    }

    LOG.debug("Finished %s: %s, duration %d",
        name,
        succeeded ? "passed" : "failed",
        timeMillis);

    TestResultSummary testResultSummary = new TestResultSummary(
        group,
        test,
        succeeded ? ResultType.SUCCESS : ResultType.FAILURE,
        timeMillis,
        null, // formatTestMessage
        null, // stackTrace,
        null, // stdOut
        null // stdErr
    );
    LOG.debug("Test result summary: %s", testResultSummary);
    testResultSummariesBuilder.put(group, testResultSummary);
  }

  public static void handleError(
      Matcher matcher,
      @SuppressWarnings("unused")
          ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder) {
      String testCase = matcher.group(2);
      LOG.debug("Test error: %s", testCase);

      // TODO(grp): Keep track of more information here.
  }
}
