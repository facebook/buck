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

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import org.junit.Test;

public class XctoolOutputParsingTest {

  private static final double EPSILON = 1e-6;

  private static XctoolOutputParsing.XctoolEventCallback eventCallbackAddingEventsToList(
      List<Object> streamedObjects) {
    return new XctoolOutputParsing.XctoolEventCallback() {
      @Override
      public void handleBeginOcunitEvent(XctoolOutputParsing.BeginOcunitEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleEndOcunitEvent(XctoolOutputParsing.EndOcunitEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleBeginTestSuiteEvent(XctoolOutputParsing.BeginTestSuiteEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleEndTestSuiteEvent(XctoolOutputParsing.EndTestSuiteEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleBeginStatusEvent(XctoolOutputParsing.StatusEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleEndStatusEvent(XctoolOutputParsing.StatusEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleBeginTestEvent(XctoolOutputParsing.BeginTestEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleEndTestEvent(XctoolOutputParsing.EndTestEvent event) {
        streamedObjects.add(event);
      }
    };
  }

  @Test
  public void streamingSimpleSuccess() throws Exception {
    Path jsonPath =
        TestDataHelper.getTestDataDirectory(this).resolve("xctool-output/simple-success.json");
    List<Object> streamedObjects = new ArrayList<>();
    try (Reader jsonReader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
      XctoolOutputParsing.streamOutputFromReader(
          jsonReader, eventCallbackAddingEventsToList(streamedObjects));
    }
    assertThat(streamedObjects, hasSize(8));

    Iterator<Object> iter = streamedObjects.iterator();
    Object nextStreamedObject;

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.StatusEvent.class));
    XctoolOutputParsing.StatusEvent beginStatusEvent =
        (XctoolOutputParsing.StatusEvent) nextStreamedObject;
    assertThat(beginStatusEvent.timestamp, closeTo(1432065853.406129, EPSILON));
    assertThat(beginStatusEvent.message, equalTo("Collecting info for testables..."));
    assertThat(beginStatusEvent.level, equalTo("Info"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.StatusEvent.class));
    XctoolOutputParsing.StatusEvent endStatusEvent =
        (XctoolOutputParsing.StatusEvent) nextStreamedObject;
    assertThat(endStatusEvent.timestamp, closeTo(1432065854.077704, EPSILON));
    assertThat(endStatusEvent.message, equalTo("Collecting info for testables..."));
    assertThat(endStatusEvent.level, equalTo("Info"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.BeginOcunitEvent.class));
    XctoolOutputParsing.BeginOcunitEvent beginOcunitEvent =
        (XctoolOutputParsing.BeginOcunitEvent) nextStreamedObject;
    assertThat(beginOcunitEvent.timestamp, closeTo(1432065854.07812, EPSILON));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.BeginTestSuiteEvent.class));
    XctoolOutputParsing.BeginTestSuiteEvent beginTestSuiteEvent =
        (XctoolOutputParsing.BeginTestSuiteEvent) nextStreamedObject;
    assertThat(beginTestSuiteEvent.timestamp, closeTo(1432065854.736793, EPSILON));
    assertThat(beginTestSuiteEvent.suite, equalTo("Toplevel Test Suite"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.BeginTestEvent.class));
    XctoolOutputParsing.BeginTestEvent beginTestEvent =
        (XctoolOutputParsing.BeginTestEvent) nextStreamedObject;
    assertThat(beginTestEvent.timestamp, closeTo(1432065854.739917, EPSILON));
    assertThat(beginTestEvent.test, equalTo("-[FooXCTest testTwoPlusTwoEqualsFour]"));
    assertThat(beginTestEvent.className, equalTo("FooXCTest"));
    assertThat(beginTestEvent.methodName, equalTo("testTwoPlusTwoEqualsFour"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.EndTestEvent.class));
    XctoolOutputParsing.EndTestEvent endTestEvent =
        (XctoolOutputParsing.EndTestEvent) nextStreamedObject;
    assertThat(endTestEvent.timestamp, closeTo(1432065854.740184, EPSILON));
    assertThat(endTestEvent.test, equalTo("-[FooXCTest testTwoPlusTwoEqualsFour]"));
    assertThat(endTestEvent.className, equalTo("FooXCTest"));
    assertThat(endTestEvent.methodName, equalTo("testTwoPlusTwoEqualsFour"));
    assertThat(endTestEvent.succeeded, is(true));
    assertThat(endTestEvent.exceptions, empty());
    assertThat(endTestEvent.totalDuration, closeTo(0.003052949905395508, EPSILON));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.EndTestSuiteEvent.class));
    XctoolOutputParsing.EndTestSuiteEvent endTestSuiteEvent =
        (XctoolOutputParsing.EndTestSuiteEvent) nextStreamedObject;
    assertThat(endTestSuiteEvent.timestamp, closeTo(1432065854.740343, EPSILON));
    assertThat(endTestSuiteEvent.suite, equalTo("Toplevel Test Suite"));
    assertThat(endTestSuiteEvent.testCaseCount, equalTo(1));
    assertThat(endTestSuiteEvent.totalFailureCount, equalTo(0));
    assertThat(endTestSuiteEvent.unexpectedExceptionCount, equalTo(0));
    assertThat(endTestSuiteEvent.totalDuration, closeTo(0.003550052642822266, EPSILON));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.EndOcunitEvent.class));
    XctoolOutputParsing.EndOcunitEvent endOcunitEvent =
        (XctoolOutputParsing.EndOcunitEvent) nextStreamedObject;
    assertThat(endOcunitEvent.timestamp, closeTo(1432065854.806839, EPSILON));
    assertThat(endOcunitEvent.message, nullValue(String.class));
    assertThat(endOcunitEvent.succeeded, is(true));
  }

  @Test
  public void streamingSimpleFailure() throws Exception {
    Path jsonPath =
        TestDataHelper.getTestDataDirectory(this).resolve("xctool-output/simple-failure.json");
    List<Object> streamedObjects = new ArrayList<>();
    try (Reader jsonReader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
      XctoolOutputParsing.streamOutputFromReader(
          jsonReader, eventCallbackAddingEventsToList(streamedObjects));
    }
    assertThat(streamedObjects, hasSize(8));

    Iterator<Object> iter = streamedObjects.iterator();
    Object nextStreamedObject;
    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.StatusEvent.class));
    XctoolOutputParsing.StatusEvent beginStatusEvent =
        (XctoolOutputParsing.StatusEvent) nextStreamedObject;
    assertThat(beginStatusEvent.timestamp, closeTo(1432065858.258645, EPSILON));
    assertThat(beginStatusEvent.message, equalTo("Collecting info for testables..."));
    assertThat(beginStatusEvent.level, equalTo("Info"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.StatusEvent.class));
    XctoolOutputParsing.StatusEvent endStatusEvent =
        (XctoolOutputParsing.StatusEvent) nextStreamedObject;
    assertThat(endStatusEvent.timestamp, closeTo(1432065859.00568, EPSILON));
    assertThat(endStatusEvent.message, equalTo("Collecting info for testables..."));
    assertThat(endStatusEvent.level, equalTo("Info"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.BeginOcunitEvent.class));
    XctoolOutputParsing.BeginOcunitEvent beginOcunitEvent =
        (XctoolOutputParsing.BeginOcunitEvent) nextStreamedObject;
    assertThat(beginOcunitEvent.timestamp, closeTo(1432065859.006029, EPSILON));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.BeginTestSuiteEvent.class));
    XctoolOutputParsing.BeginTestSuiteEvent beginTestSuiteEvent =
        (XctoolOutputParsing.BeginTestSuiteEvent) nextStreamedObject;
    assertThat(beginTestSuiteEvent.timestamp, closeTo(1432065859.681727, EPSILON));
    assertThat(beginTestSuiteEvent.suite, equalTo("Toplevel Test Suite"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.BeginTestEvent.class));
    XctoolOutputParsing.BeginTestEvent beginTestEvent =
        (XctoolOutputParsing.BeginTestEvent) nextStreamedObject;
    assertThat(beginTestEvent.timestamp, closeTo(1432065859.684965, EPSILON));
    assertThat(beginTestEvent.test, equalTo("-[FooXCTest testTwoPlusTwoEqualsFive]"));
    assertThat(beginTestEvent.className, equalTo("FooXCTest"));
    assertThat(beginTestEvent.methodName, equalTo("testTwoPlusTwoEqualsFive"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.EndTestEvent.class));
    XctoolOutputParsing.EndTestEvent endTestEvent =
        (XctoolOutputParsing.EndTestEvent) nextStreamedObject;
    assertThat(endTestEvent.timestamp, closeTo(1432065859.685524, EPSILON));
    assertThat(endTestEvent.totalDuration, closeTo(0.003522038459777832, EPSILON));
    assertThat(endTestEvent.test, equalTo("-[FooXCTest testTwoPlusTwoEqualsFive]"));
    assertThat(endTestEvent.className, equalTo("FooXCTest"));
    assertThat(endTestEvent.methodName, equalTo("testTwoPlusTwoEqualsFive"));
    assertThat(endTestEvent.succeeded, is(false));
    assertThat(endTestEvent.exceptions, hasSize(1));

    nextStreamedObject = iter.next();
    XctoolOutputParsing.TestException testException = endTestEvent.exceptions.get(0);
    assertThat(testException.lineNumber, equalTo(9));
    assertThat(testException.filePathInProject, equalTo("FooXCTest.m"));
    assertThat(
        testException.reason,
        equalTo(
            "((2 + 2) equal to (5)) failed: (\"4\") is not equal to (\"5\") - Two plus two "
                + "equals five"));

    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.EndTestSuiteEvent.class));
    XctoolOutputParsing.EndTestSuiteEvent endTestSuiteEvent =
        (XctoolOutputParsing.EndTestSuiteEvent) nextStreamedObject;
    assertThat(endTestSuiteEvent.timestamp, closeTo(1432065859.685689, EPSILON));
    assertThat(endTestSuiteEvent.suite, equalTo("Toplevel Test Suite"));
    assertThat(endTestSuiteEvent.testCaseCount, equalTo(1));
    assertThat(endTestSuiteEvent.totalFailureCount, equalTo(1));
    assertThat(endTestSuiteEvent.unexpectedExceptionCount, equalTo(0));
    assertThat(endTestSuiteEvent.totalDuration, closeTo(0.003962039947509766, EPSILON));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctoolOutputParsing.EndOcunitEvent.class));
    XctoolOutputParsing.EndOcunitEvent endOcunitEvent =
        (XctoolOutputParsing.EndOcunitEvent) nextStreamedObject;
    assertThat(endOcunitEvent.timestamp, closeTo(1432065859.751992, EPSILON));
    assertThat(endOcunitEvent.message, nullValue(String.class));
    assertThat(endOcunitEvent.succeeded, is(false));
  }

  @Test
  public void streamingEmptyReaderDoesNotCauseFailure() {
    List<Object> streamedObjects = new ArrayList<>();
    XctoolOutputParsing.streamOutputFromReader(
        new StringReader(""), eventCallbackAddingEventsToList(streamedObjects));
    assertThat(streamedObjects, is(empty()));
  }

  @Test
  public void validEventParsesToStatusMessage() {
    XctoolOutputParsing.StatusEvent statusEvent = new XctoolOutputParsing.StatusEvent();
    statusEvent.message = "Hello world";
    statusEvent.level = "Info";
    statusEvent.timestamp = 123.456;

    Optional<TestStatusMessage> testStatusMessage =
        XctoolOutputParsing.testStatusMessageForStatusEvent(statusEvent);
    assertThat(
        testStatusMessage,
        equalTo(Optional.of(TestStatusMessage.of("Hello world", Level.INFO, 123456L))));
  }

  @Test
  public void invalidEventLevelParsesToAbsent() {
    XctoolOutputParsing.StatusEvent statusEvent = new XctoolOutputParsing.StatusEvent();
    statusEvent.message = "Hello world";
    statusEvent.level = "BALEETED";
    statusEvent.timestamp = 123.456;

    Optional<TestStatusMessage> testStatusMessage =
        XctoolOutputParsing.testStatusMessageForStatusEvent(statusEvent);
    assertThat(testStatusMessage, equalTo(Optional.empty()));
  }

  @Test
  public void invalidEventMessageParsesToAbsent() {
    XctoolOutputParsing.StatusEvent statusEvent = new XctoolOutputParsing.StatusEvent();
    statusEvent.message = null;
    statusEvent.level = "Info";
    statusEvent.timestamp = 123.456;

    Optional<TestStatusMessage> testStatusMessage =
        XctoolOutputParsing.testStatusMessageForStatusEvent(statusEvent);
    assertThat(testStatusMessage, equalTo(Optional.empty()));
  }
}
