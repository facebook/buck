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
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonStreamParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Utility class to parse the output from {@code xctool -reporter json-stream}.
 */
public class XctoolOutputParsing {

  private static final Logger LOG = Logger.get(XctoolOutputParsing.class);

  // Utility class; do not instantiate.
  private XctoolOutputParsing() { }

  // Checkstyle thinks any class named "*Exception" is an exception and
  // must only have final fields..
  @SuppressWarnings("checkstyle:mutableexception")
  public static class TestException {
    @Nullable
    public String filePathInProject = null;
    public int lineNumber = -1;
    @Nullable
    public String reason = null;
  }

  public static class BeginOcunitEvent {
    public double timestamp = -1;
  }

  public static class EndOcunitEvent {
    public double timestamp = -1;
    @Nullable
    public String message = null;
    public boolean succeeded = false;
  }

  public static class BeginTestSuiteEvent {
    public double timestamp = -1;
    @Nullable
    public String suite = null;
  }

  public static class EndTestSuiteEvent {
    public double timestamp = -1;
    public double totalDuration = -1;
    public double testDuration = -1;
    @Nullable
    public String suite = null;
    public int testCaseCount = -1;
    public int totalFailureCount = -1;
    public int unexpectedExceptionCount = -1;
    public boolean succeeded = false;
  }

  public static class BeginTestEvent {
    public double timestamp = -1;
    @Nullable
    public String test = null;
    @Nullable
    public String className = null;
    @Nullable
    public String methodName = null;
  }

  public static class EndTestEvent {
    public double totalDuration = -1;
    public double timestamp = -1;
    @Nullable
    public String test = null;
    @Nullable
    public String className = null;
    @Nullable
    public String methodName = null;
    @Nullable
    public String output = null;
    public boolean succeeded = false;
    public List<TestException> exceptions = new ArrayList<>();
  }

  /**
   * Callbacks invoked with events emitted by {@code xctool -reporter json-stream}.
   */
  public interface XctoolEventCallback {
    void handleBeginOcunitEvent(BeginOcunitEvent event);
    void handleEndOcunitEvent(EndOcunitEvent event);
    void handleBeginTestSuiteEvent(BeginTestSuiteEvent event);
    void handleEndTestSuiteEvent(EndTestSuiteEvent event);
    void handleBeginTestEvent(BeginTestEvent event);
    void handleEndTestEvent(EndTestEvent event);
  }

  /**
   * Decodes a stream of JSON objects as produced by {@code xctool -reporter json-stream}
   * and invokes the callbacks in {@code eventCallback} with each event in the stream.
   */
  public static void streamOutputFromReader(
      Reader reader,
      XctoolEventCallback eventCallback) {
    Gson gson = new Gson();
    JsonStreamParser streamParser = new JsonStreamParser(reader);
    while (streamParser.hasNext()) {
      try {
        dispatchEventCallback(gson, streamParser.next(), eventCallback);
      } catch (JsonParseException e) {
        LOG.warn(e, "Couldn't parse xctool JSON stream");
      }
    }
  }

  private static void dispatchEventCallback(
      Gson gson,
      JsonElement element,
      XctoolEventCallback eventCallback)
        throws JsonParseException {
    LOG.debug("Parsing xctool event: %s", element);
    if (!element.isJsonObject()) {
      LOG.warn("Couldn't parse JSON object from xctool event: %s", element);
      return;
    }
    JsonObject object = element.getAsJsonObject();
    if (!object.has("event")) {
      LOG.warn("Couldn't parse JSON event from xctool event: %s", element);
      return;
    }
    JsonElement event = object.get("event");
    if (event == null || !event.isJsonPrimitive()) {
      LOG.warn("Couldn't parse event field from xctool event: %s", element);
      return;
    }
    switch (event.getAsString()) {
      case "begin-ocunit":
        eventCallback.handleBeginOcunitEvent(gson.fromJson(element, BeginOcunitEvent.class));
        break;
      case "end-ocunit":
        eventCallback.handleEndOcunitEvent(gson.fromJson(element, EndOcunitEvent.class));
        break;
      case "begin-test-suite":
        eventCallback.handleBeginTestSuiteEvent(gson.fromJson(element, BeginTestSuiteEvent.class));
        break;
      case "end-test-suite":
        eventCallback.handleEndTestSuiteEvent(gson.fromJson(element, EndTestSuiteEvent.class));
        break;
      case "begin-test":
        eventCallback.handleBeginTestEvent(gson.fromJson(element, BeginTestEvent.class));
        break;
      case "end-test":
        eventCallback.handleEndTestEvent(gson.fromJson(element, EndTestEvent.class));
        break;
    }
  }

  public static TestResultSummary testResultSummaryForEndTestEvent(EndTestEvent endTestEvent) {
    long timeMillis = (long) (endTestEvent.totalDuration * TimeUnit.SECONDS.toMillis(1));
    TestResultSummary testResultSummary = new TestResultSummary(
        Preconditions.checkNotNull(endTestEvent.className),
        Preconditions.checkNotNull(endTestEvent.test),
        endTestEvent.succeeded ? ResultType.SUCCESS : ResultType.FAILURE,
        timeMillis,
        formatTestMessage(endTestEvent),
        null, // stackTrace,
        formatTestStdout(endTestEvent),
        null // stdErr
    );
    LOG.debug("Test result summary: %s", testResultSummary);
    return testResultSummary;
  }

  @Nullable
  private static String formatTestMessage(EndTestEvent endTestEvent) {
    if (endTestEvent.exceptions != null &&
        !endTestEvent.exceptions.isEmpty()) {
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

  public static Optional<TestResultSummary> internalErrorForEndOcunitEvent(
      EndOcunitEvent endOcunitEvent) {
    if (endOcunitEvent.succeeded || endOcunitEvent.message == null) {
      // We only care about failures with a message. (Failures without a message always
      // happen with any random test failure.)
      return Optional.absent();
    }

    TestResultSummary testResultSummary = new TestResultSummary(
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
