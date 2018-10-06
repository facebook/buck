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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.google.common.collect.ImmutableList;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Utility class to parse the output from {@code xctest}. */
class XctestOutputParsing {

  private static final Logger LOG = Logger.get(XctestOutputParsing.class);

  // Utility class; do not instantiate.
  private XctestOutputParsing() {}

  private static final Pattern SUITE_STARTED_PATTERN =
      Pattern.compile("Test Suite '(.*)' started at (.*)");
  private static final Pattern SUITE_FINISHED_PATTERN =
      Pattern.compile("Test Suite '(.*)' (finished|passed|failed) at (.*).");
  private static final Pattern SUITE_FINISHED_DETAILS_PATTERN =
      Pattern.compile(
          "\t (Executed|Passed) (.*) tests?, with (.*) failures? \\((.*) unexpected\\) "
              + "in (.*) \\((.*)\\) seconds");
  private static final Pattern CASE_STARTED_PATTERN =
      Pattern.compile("Test Case '(.*)' started\\.?");
  private static final Pattern CASE_FINISHED_PATTERN =
      Pattern.compile("Test Case '(.*)' (passed|failed) \\((.*) seconds\\)\\.");
  private static final Pattern ERROR_PATTERN = Pattern.compile("(.*):(.*): error: (.*) : (.*)");
  private static final Pattern CASE_PATTERN = Pattern.compile("-\\[(\\S+) (\\S+)\\]");

  public static class TestError {
    @Nullable public String filePathInProject = null;
    public int lineNumber = -1;
    @Nullable public String reason = null;
  }

  public static class BeginXctestEvent {}

  public static class EndXctestEvent {}

  public static class BeginTestSuiteEvent {
    @Nullable public String suite = null;
  }

  public static class EndTestSuiteEvent {
    public double totalDuration = -1;
    public double testDuration = -1;
    @Nullable public String suite = null;
    public int testCaseCount = -1;
    public int totalFailureCount = -1;
    public int unexpectedExceptionCount = -1;
  }

  public static class BeginTestCaseEvent {
    @Nullable public String test = null;
    @Nullable public String className = null;
    @Nullable public String methodName = null;
  }

  public static class EndTestCaseEvent {
    public double totalDuration = -1;
    @Nullable public String test = null;
    @Nullable public String className = null;
    @Nullable public String methodName = null;
    @Nullable public String output = null;
    public boolean succeeded = false;
    @Nullable public List<TestError> exceptions = null;
  }

  /** Callbacks invoked with events emitted by {@code xctest}. */
  public interface XctestEventCallback {
    void handleBeginXctestEvent(BeginXctestEvent event);

    void handleEndXctestEvent(EndXctestEvent event);

    void handleBeginTestSuiteEvent(BeginTestSuiteEvent event);

    void handleEndTestSuiteEvent(EndTestSuiteEvent event);

    void handleBeginTestCaseEvent(BeginTestCaseEvent event);

    void handleEndTestCaseEvent(EndTestCaseEvent event);
  }

  public static TestResultSummary testResultSummaryForEndTestCaseEvent(EndTestCaseEvent event) {
    long timeMillis = (long) (event.totalDuration * TimeUnit.SECONDS.toMillis(1));
    TestResultSummary testResultSummary =
        new TestResultSummary(
            Optional.ofNullable(event.className).orElse(Objects.requireNonNull(event.test)),
            Optional.ofNullable(event.methodName).orElse(Objects.requireNonNull(event.test)),
            event.succeeded ? ResultType.SUCCESS : ResultType.FAILURE,
            timeMillis,
            formatTestMessage(event),
            null, // stackTrace,
            formatTestOutput(event),
            null // stderr
            );
    LOG.debug("Test result summary: %s", testResultSummary);
    return testResultSummary;
  }

  @Nullable
  private static String formatTestMessage(EndTestCaseEvent endTestCaseEvent) {
    if (endTestCaseEvent.exceptions != null && !endTestCaseEvent.exceptions.isEmpty()) {
      StringBuilder exceptionsMessage = new StringBuilder();
      for (TestError testException : endTestCaseEvent.exceptions) {
        if (exceptionsMessage.length() > 0) {
          exceptionsMessage.append('\n');
        }
        exceptionsMessage
            .append(testException.filePathInProject)
            .append(':')
            .append(testException.lineNumber)
            .append(": ")
            .append(testException.reason);
      }
      return exceptionsMessage.toString();
    }
    return null;
  }

  @Nullable
  private static String formatTestOutput(EndTestCaseEvent event) {
    if (event.output != null && !event.output.isEmpty()) {
      return event.output;
    } else {
      return null;
    }
  }

  /**
   * Decodes a stream of output lines as produced by {@code xctest} and invokes the callbacks in
   * {@code eventCallback} with each event in the stream.
   */
  public static void streamOutput(Reader output, XctestEventCallback eventCallback) {
    try {
      LOG.debug("Began parsing xctest output");
      BeginXctestEvent beginEvent = new BeginXctestEvent();
      eventCallback.handleBeginXctestEvent(beginEvent);

      try (BufferedReader outputBufferedReader = new BufferedReader(output)) {
        LOG.debug("Created buffered readers");

        String line;
        while ((line = outputBufferedReader.readLine()) != null) {
          handleLine(line, outputBufferedReader, eventCallback);
        }
      }

      EndXctestEvent endEvent = new EndXctestEvent();
      eventCallback.handleEndXctestEvent(endEvent);
      LOG.debug("Finished parsing xctest output");
    } catch (IOException e) {
      LOG.warn(e, "Couldn't parse xctest output");
    }
  }

  private static void handleLine(
      String line, BufferedReader outputBufferedReader, XctestEventCallback eventCallback) {
    LOG.debug("Parsing xctest line: %s", line);

    Matcher suiteStartedMatcher = XctestOutputParsing.SUITE_STARTED_PATTERN.matcher(line);
    if (suiteStartedMatcher.find()) {
      handleSuiteStarted(suiteStartedMatcher, eventCallback);
      return;
    }

    Matcher suiteFinishedMatcher = XctestOutputParsing.SUITE_FINISHED_PATTERN.matcher(line);
    if (suiteFinishedMatcher.find()) {
      handleSuiteFinished(suiteFinishedMatcher, outputBufferedReader, eventCallback);
      return;
    }

    Matcher caseStartedMatcher = XctestOutputParsing.CASE_STARTED_PATTERN.matcher(line);
    if (caseStartedMatcher.find()) {
      handleCaseStarted(caseStartedMatcher, outputBufferedReader, eventCallback);
      return;
    }

    LOG.debug("Line was not matched");
  }

  public static void handleSuiteStarted(Matcher matcher, XctestEventCallback eventCallback) {
    BeginTestSuiteEvent event = new BeginTestSuiteEvent();
    event.suite = matcher.group(1);
    eventCallback.handleBeginTestSuiteEvent(event);

    LOG.debug("Started suite: %s", event.suite);
  }

  public static void handleSuiteFinished(
      Matcher matcher, BufferedReader outputBufferedReader, XctestEventCallback eventCallback) {
    EndTestSuiteEvent event = new EndTestSuiteEvent();
    event.suite = matcher.group(1);

    String line;
    try {
      line = outputBufferedReader.readLine();
      if (line == null) {
        LOG.error("Missing suite finished details line");
        return;
      }
    } catch (IOException e) {
      LOG.error("Error reading finished details line");
      return;
    }

    Matcher detailsMatcher = XctestOutputParsing.SUITE_FINISHED_DETAILS_PATTERN.matcher(line);
    if (!detailsMatcher.find()) {
      LOG.error("Invalid suite finished details line: %s", line);
      return;
    }

    try {
      event.testCaseCount = Integer.parseInt(detailsMatcher.group(2));
    } catch (NumberFormatException e) {
      LOG.error("Invalid test case count: %s", detailsMatcher.group(2));
    }
    try {
      event.totalFailureCount = Integer.parseInt(detailsMatcher.group(3));
    } catch (NumberFormatException e) {
      LOG.error("Invalid total failures: %s", detailsMatcher.group(3));
    }
    try {
      event.unexpectedExceptionCount = Integer.parseInt(detailsMatcher.group(4));
    } catch (NumberFormatException e) {
      LOG.error("Invalid unexpected failures: %s", detailsMatcher.group(4));
    }
    try {
      event.testDuration = Double.parseDouble(detailsMatcher.group(5));
    } catch (NumberFormatException e) {
      LOG.error("Invalid test duration: %s", detailsMatcher.group(5));
    }
    try {
      event.totalDuration = Double.parseDouble(detailsMatcher.group(6));
    } catch (NumberFormatException e) {
      LOG.error("Invalid total duration: %s", detailsMatcher.group(6));
    }

    eventCallback.handleEndTestSuiteEvent(event);

    LOG.debug("Finished suite: %s", event.suite);
  }

  public static void handleCaseStarted(
      Matcher matcher, BufferedReader outputBufferedReader, XctestEventCallback eventCallback) {
    BeginTestCaseEvent event = new BeginTestCaseEvent();
    event.test = matcher.group(1);

    Matcher nameMatcher = XctestOutputParsing.CASE_PATTERN.matcher(event.test);
    if (nameMatcher.find()) {
      event.className = nameMatcher.group(1);
      event.methodName = nameMatcher.group(2);
    }

    eventCallback.handleBeginTestCaseEvent(event);
    LOG.debug("Started test case: %s", event.test);

    StringBuilder output = new StringBuilder();
    ImmutableList.Builder<TestError> exceptions = new ImmutableList.Builder<TestError>();

    try {
      String line;
      while ((line = outputBufferedReader.readLine()) != null) {
        LOG.debug("Parsing xctest case line: %s", line);

        Matcher caseFinishedMatcher = XctestOutputParsing.CASE_FINISHED_PATTERN.matcher(line);
        if (caseFinishedMatcher.find()) {
          handleCaseFinished(
              caseFinishedMatcher, exceptions.build(), output.toString(), eventCallback);
          return;
        }

        Matcher errorMatcher = XctestOutputParsing.ERROR_PATTERN.matcher(line);
        if (errorMatcher.find()) {
          handleError(errorMatcher, exceptions);
          continue;
        }

        output.append(line);
        output.append("\n");
      }
    } catch (IOException e) {
      LOG.error("Error reading line");
    }

    LOG.error("Test case did not end!");

    // Synthesize a failure to note the test crashed or stopped.
    EndTestCaseEvent endEvent = new EndTestCaseEvent();
    endEvent.test = event.test;
    endEvent.className = event.className;
    endEvent.methodName = event.methodName;
    endEvent.totalDuration = 0;
    endEvent.output = output.toString();
    endEvent.succeeded = false; // failure
    endEvent.exceptions = exceptions.build();
    eventCallback.handleEndTestCaseEvent(endEvent);
  }

  public static void handleCaseFinished(
      Matcher matcher,
      ImmutableList<TestError> exceptions,
      String output,
      XctestEventCallback eventCallback) {
    EndTestCaseEvent event = new EndTestCaseEvent();
    event.test = matcher.group(1);

    Matcher nameMatcher = XctestOutputParsing.CASE_PATTERN.matcher(event.test);
    if (nameMatcher.find()) {
      event.className = nameMatcher.group(1);
      event.methodName = nameMatcher.group(2);
    }

    try {
      event.totalDuration = Double.parseDouble(matcher.group(3));
    } catch (NumberFormatException e) {
      LOG.warn("Invalid duration: %s", matcher.group(3));
    }
    event.output = output;
    event.succeeded = matcher.group(2).equals("passed");
    event.exceptions = exceptions;
    eventCallback.handleEndTestCaseEvent(event);

    LOG.debug(
        "Finished %s: %s, duration %f",
        event.test, event.succeeded ? "passed" : "failed", event.totalDuration);
  }

  public static void handleError(Matcher matcher, ImmutableList.Builder<TestError> exceptions) {
    TestError error = new TestError();
    error.filePathInProject = matcher.group(1);
    try {
      error.lineNumber = Integer.parseInt(matcher.group(2));
    } catch (NumberFormatException e) {
      LOG.warn("Invalid line number: %s", matcher.group(2));
    }
    error.reason = matcher.group(4);
    exceptions.add(error);

    LOG.debug("Test error: %s:%d: %s", error.filePathInProject, error.lineNumber, error.reason);
  }
}
