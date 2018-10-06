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
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.LenientBooleanJsonDeserializer;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.RuntimeJsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nullable;

/** Utility class to parse the output from {@code xctool -reporter json-stream}. */
class XctoolOutputParsing {

  private static final Logger LOG = Logger.get(XctoolOutputParsing.class);
  private static final String XCTOOL_CRASH_REPORT_KEY = "CRASH REPORT:";

  // Utility class; do not instantiate.
  private XctoolOutputParsing() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class TestException {
    @Nullable public String filePathInProject = null;
    public int lineNumber = -1;
    @Nullable public String reason = null;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class BeginOcunitEvent {
    public double timestamp = -1;
    @Nullable public String targetName = null;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class EndOcunitEvent {
    public double timestamp = -1;
    @Nullable public String message = null;

    @JsonDeserialize(using = LenientBooleanJsonDeserializer.class)
    public boolean succeeded = false;

    @Nullable public String targetName = null;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class StatusEvent {
    public double timestamp = -1;
    @Nullable public String message = null;
    @Nullable public String level = null;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class BeginTestSuiteEvent {
    public double timestamp = -1;
    @Nullable public String suite = null;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class EndTestSuiteEvent {
    public double timestamp = -1;
    public double totalDuration = -1;
    public double testDuration = -1;
    @Nullable public String suite = null;
    public int testCaseCount = -1;
    public int totalFailureCount = -1;
    public int unexpectedExceptionCount = -1;

    @JsonDeserialize(using = LenientBooleanJsonDeserializer.class)
    public boolean succeeded = false;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class BeginTestEvent {
    public double timestamp = -1;
    @Nullable public String test = null;
    @Nullable public String className = null;
    @Nullable public String methodName = null;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class EndTestEvent {
    public double totalDuration = -1;
    public double timestamp = -1;
    @Nullable public String test = null;
    @Nullable public String className = null;
    @Nullable public String methodName = null;
    @Nullable public String output = null;

    @JsonDeserialize(using = LenientBooleanJsonDeserializer.class)
    public boolean succeeded = false;

    public List<TestException> exceptions = new ArrayList<>();
  }

  /** Callbacks invoked with events emitted by {@code xctool -reporter json-stream}. */
  public interface XctoolEventCallback {
    void handleBeginOcunitEvent(BeginOcunitEvent event);

    void handleEndOcunitEvent(EndOcunitEvent event);

    void handleBeginStatusEvent(StatusEvent event);

    void handleEndStatusEvent(StatusEvent event);

    void handleBeginTestSuiteEvent(BeginTestSuiteEvent event);

    void handleEndTestSuiteEvent(EndTestSuiteEvent event);

    void handleBeginTestEvent(BeginTestEvent event);

    void handleEndTestEvent(EndTestEvent event);
  }

  /**
   * Decodes a stream of JSON objects as produced by {@code xctool -reporter json-stream} and
   * invokes the callbacks in {@code eventCallback} with each event in the stream.
   */
  public static void streamOutputFromReader(Reader reader, XctoolEventCallback eventCallback) {
    try {
      MappingIterator<ImmutableMap<String, Object>> it =
          ObjectMappers.READER
              .forType(new TypeReference<ImmutableMap<String, Object>>() {})
              .readValues(reader);
      while (it.hasNext()) {
        ImmutableMap<String, Object> element;
        try {
          element = it.next();
        } catch (RuntimeJsonMappingException e) {
          LOG.warn(e, "Couldn't parse JSON object from xctool event");
          continue;
        }
        dispatchEventCallback(element, eventCallback);
      }
    } catch (IOException e) {
      LOG.warn(e, "Couldn't parse xctool JSON stream");
    }
  }

  private static void dispatchEventCallback(
      ImmutableMap<String, Object> object, XctoolEventCallback eventCallback) {
    LOG.debug("Parsing xctool event: %s", object);
    if (!object.containsKey("event")) {
      LOG.warn("Couldn't parse JSON event from xctool event: %s", object);
      return;
    }
    Object event = object.get("event");
    if (!(event instanceof String)) {
      LOG.warn("Couldn't parse event field from xctool event: %s", object);
      return;
    }
    switch ((String) event) {
      case "begin-ocunit":
        eventCallback.handleBeginOcunitEvent(
            ObjectMappers.convertValue(object, BeginOcunitEvent.class));
        break;
      case "end-ocunit":
        eventCallback.handleEndOcunitEvent(
            ObjectMappers.convertValue(object, EndOcunitEvent.class));
        break;
      case "begin-status":
        eventCallback.handleBeginStatusEvent(ObjectMappers.convertValue(object, StatusEvent.class));
        break;
      case "end-status":
        eventCallback.handleEndStatusEvent(ObjectMappers.convertValue(object, StatusEvent.class));
        break;
      case "begin-test-suite":
        eventCallback.handleBeginTestSuiteEvent(
            ObjectMappers.convertValue(object, BeginTestSuiteEvent.class));
        break;
      case "end-test-suite":
        eventCallback.handleEndTestSuiteEvent(
            ObjectMappers.convertValue(object, EndTestSuiteEvent.class));
        break;
      case "begin-test":
        eventCallback.handleBeginTestEvent(
            ObjectMappers.convertValue(object, BeginTestEvent.class));
        break;
      case "end-test":
        eventCallback.handleEndTestEvent(ObjectMappers.convertValue(object, EndTestEvent.class));
        break;
    }
  }

  public static Optional<TestStatusMessage> testStatusMessageForStatusEvent(
      StatusEvent statusEvent) {
    if (statusEvent.message == null || statusEvent.level == null) {
      LOG.warn("Ignoring invalid status (message or level is null): %s", statusEvent);
      return Optional.empty();
    }
    Level level;
    switch (statusEvent.level) {
      case "Verbose":
        level = Level.FINER;
        break;
      case "Debug":
        level = Level.FINE;
        break;
      case "Info":
        level = Level.INFO;
        break;
      case "Warning":
        level = Level.WARNING;
        break;
      case "Error":
        level = Level.SEVERE;
        break;
      default:
        LOG.warn("Ignoring invalid status (unknown level %s)", statusEvent.level);
        return Optional.empty();
    }
    long timeMillis = (long) (statusEvent.timestamp * TimeUnit.SECONDS.toMillis(1));
    return Optional.of(TestStatusMessage.of(statusEvent.message, level, timeMillis));
  }

  public static TestResultSummary testResultSummaryForEndTestEvent(EndTestEvent endTestEvent) {
    long timeMillis = (long) (endTestEvent.totalDuration * TimeUnit.SECONDS.toMillis(1));
    TestResultSummary testResultSummary =
        new TestResultSummary(
            Objects.requireNonNull(endTestEvent.className),
            Objects.requireNonNull(endTestEvent.test),
            endTestEvent.succeeded ? ResultType.SUCCESS : ResultType.FAILURE,
            timeMillis,
            formatTestMessage(endTestEvent),
            formatTestStackTrace(endTestEvent),
            formatTestStdout(endTestEvent),
            null // stdErr
            );
    LOG.debug("Test result summary: %s", testResultSummary);
    return testResultSummary;
  }

  @Nullable
  private static String formatTestMessage(EndTestEvent endTestEvent) {
    if (endTestEvent.exceptions != null && !endTestEvent.exceptions.isEmpty()) {
      StringBuilder exceptionsMessage = new StringBuilder();
      for (TestException testException : endTestEvent.exceptions) {
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
  private static String formatTestStdout(EndTestEvent endTestEvent) {
    if (endTestEvent.output != null && !endTestEvent.output.isEmpty()) {
      return endTestEvent.output;
    } else {
      return null;
    }
  }

  @Nullable
  private static String formatTestStackTrace(EndTestEvent endTestEvent) {
    String output = formatTestStdout(endTestEvent);
    if (!endTestEvent.succeeded && output != null && output.contains(XCTOOL_CRASH_REPORT_KEY)) {
      return output;
    } else {
      return null;
    }
  }

  public static Optional<TestResultSummary> internalErrorForEndOcunitEvent(
      EndOcunitEvent endOcunitEvent) {
    if (endOcunitEvent.succeeded || endOcunitEvent.message == null) {
      // We only care about failures with a message. (Failures without a message always
      // happen with any random test failure.)
      return Optional.empty();
    }

    TestResultSummary testResultSummary =
        new TestResultSummary(
            "Internal error from test runner",
            "main",
            ResultType.FAILURE,
            0L,
            endOcunitEvent.message,
            null, // stackTrace,
            null, // stdOut
            null // stdErr
            );
    LOG.debug("OCUnit/XCTest internal failure: %s", testResultSummary);
    return Optional.of(testResultSummary);
  }
}
