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
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonStreamParser;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
  private static class TestException {
    public String filePathInProject = null;
    public int lineNumber = -1;
    public String reason = null;
  }

  private static class EndTestEvent {
    public String test = null;
    public String className = null;
    public String output = null;
    public boolean succeeded = false;
    public double totalDuration = -1;
    public List<TestException> exceptions = new ArrayList<>();
  }

  private static class EndOcunitEvent {
    public String message = null;
    public boolean succeeded = false;
  }

  /**
   * Parses a stream of JSON objects as produced by {@code xctool -reporter json-stream}
   * and converts them into {@link TestCaseSummary} objects.
   */
  public static List<TestCaseSummary> parseOutputFromReader(Reader reader)
    throws IOException {
    Gson gson = new Gson();
    ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder =
        ImmutableListMultimap.builder();

    JsonStreamParser streamParser = new JsonStreamParser(reader);
    while (streamParser.hasNext()) {
      try {
        handleEvent(gson, streamParser.next(), testResultSummariesBuilder);
      } catch (JsonParseException e) {
        LOG.warn(e, "Couldn't parse xctool JSON stream");
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

  private static void handleEvent(
      Gson gson,
      JsonElement element,
      ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder)
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
      case "end-test":
        EndTestEvent endTestEvent = gson.fromJson(element, EndTestEvent.class);
        handleEndTestEvent(endTestEvent, testResultSummariesBuilder);
        break;
      case "end-ocunit":
        EndOcunitEvent endOcunitEvent = gson.fromJson(element, EndOcunitEvent.class);
        handleEndOcunitEvent(endOcunitEvent, testResultSummariesBuilder);
        break;
    }
  }

  private static void handleEndTestEvent(
      EndTestEvent endTestEvent,
      ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder) {
    long timeMillis = (long) (endTestEvent.totalDuration * TimeUnit.SECONDS.toMillis(1));
    TestResultSummary testResultSummary = new TestResultSummary(
        endTestEvent.className,
        endTestEvent.test,
        endTestEvent.succeeded ? ResultType.SUCCESS : ResultType.FAILURE,
        timeMillis,
        formatTestMessage(endTestEvent),
        null, // stackTrace,
        formatTestStdout(endTestEvent),
        null // stdErr
    );
    LOG.debug("Test result summary: %s", testResultSummary);
    testResultSummariesBuilder.put(endTestEvent.className, testResultSummary);
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

  private static void handleEndOcunitEvent(
      EndOcunitEvent endOcunitEvent,
      ImmutableListMultimap.Builder<String, TestResultSummary> testResultSummariesBuilder) {
    if (endOcunitEvent.succeeded || endOcunitEvent.message == null) {
      // We only care about failures with a message. (Failures without a message always
      // happen with any random test failure.)
      return;
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
    testResultSummariesBuilder.put("Internal error from test runner", testResultSummary);
  }
}
