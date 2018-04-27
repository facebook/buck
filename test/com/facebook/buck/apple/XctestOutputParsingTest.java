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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.hamcrest.Matcher;
import org.junit.Test;

public class XctestOutputParsingTest {

  @Test
  @SuppressWarnings("unchecked")
  public void mixedPassAndFailReturnsMixedResultSummary() throws Exception {
    Path outputPath =
        TestDataHelper.getTestDataDirectory(this).resolve("xctest-output/mixed-pass-and-fail.txt");
    try (Reader outputReader = Files.newBufferedReader(outputPath, StandardCharsets.UTF_8)) {
      TestCaseSummariesBuildingXctestEventHandler xctestEventHandler =
          new TestCaseSummariesBuildingXctestEventHandler(TestRule.NOOP_REPORTING_CALLBACK);
      XctestOutputParsing.streamOutput(outputReader, xctestEventHandler);

      List<TestCaseSummary> summaries = xctestEventHandler.getTestCaseSummaries();
      assertThat(summaries, hasSize(2));

      Matcher<TestResultSummary> isOtherTestsTestSomethingSuccess =
          allOf(
              hasProperty("testCaseName", equalTo("OtherTests")),
              hasProperty("testName", equalTo("testSomething")),
              hasProperty("type", equalTo(ResultType.SUCCESS)),
              hasProperty("time", equalTo(3L)),
              hasProperty("message", nullValue(String.class)),
              hasProperty("stacktrace", nullValue(String.class)),
              hasProperty("stdOut", nullValue(String.class)),
              hasProperty("stdErr", nullValue(String.class)));

      List<TestResultSummary> otherTestsResults = summaries.get(0).getTestResults();
      assertThat(otherTestsResults, contains(isOtherTestsTestSomethingSuccess));

      Matcher<TestResultSummary> isSomeTestsTestWillFail =
          allOf(
              hasProperty("testCaseName", equalTo("SomeTests")),
              hasProperty("testName", equalTo("testWillFail")),
              hasProperty("type", equalTo(ResultType.FAILURE)),
              hasProperty("time", equalTo(0L)),
              hasProperty("message", nullValue(String.class)),
              hasProperty("stacktrace", nullValue(String.class)),
              hasProperty("stdOut", nullValue(String.class)),
              hasProperty("stdErr", nullValue(String.class)));

      Matcher<TestResultSummary> isSomeTestsTestWillPass =
          allOf(
              hasProperty("testCaseName", equalTo("SomeTests")),
              hasProperty("testName", equalTo("testWillPass")),
              hasProperty("type", equalTo(ResultType.SUCCESS)),
              hasProperty("time", equalTo(0L)),
              hasProperty("message", nullValue(String.class)),
              hasProperty("stacktrace", nullValue(String.class)),
              hasProperty("stdOut", nullValue(String.class)),
              hasProperty("stdErr", nullValue(String.class)));

      List<TestResultSummary> someTestsResults = summaries.get(1).getTestResults();
      assertThat(someTestsResults, contains(isSomeTestsTestWillFail, isSomeTestsTestWillPass));
    }
  }

  private static final double EPSILON = 1e-6;

  private static XctestOutputParsing.XctestEventCallback eventCallbackAddingEventsToList(
      List<Object> streamedObjects) {
    return new XctestOutputParsing.XctestEventCallback() {
      @Override
      public void handleBeginXctestEvent(XctestOutputParsing.BeginXctestEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleEndXctestEvent(XctestOutputParsing.EndXctestEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleBeginTestSuiteEvent(XctestOutputParsing.BeginTestSuiteEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleEndTestSuiteEvent(XctestOutputParsing.EndTestSuiteEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleBeginTestCaseEvent(XctestOutputParsing.BeginTestCaseEvent event) {
        streamedObjects.add(event);
      }

      @Override
      public void handleEndTestCaseEvent(XctestOutputParsing.EndTestCaseEvent event) {
        streamedObjects.add(event);
      }
    };
  }

  @Test
  public void streamingSimpleSuccess() throws Exception {
    Path outputPath =
        TestDataHelper.getTestDataDirectory(this).resolve("xctest-output/simple-success.txt");
    List<Object> streamedObjects = new ArrayList<>();
    try (Reader outputReader = Files.newBufferedReader(outputPath, StandardCharsets.UTF_8)) {
      XctestOutputParsing.streamOutput(
          outputReader, eventCallbackAddingEventsToList(streamedObjects));
    }
    assertThat(streamedObjects, hasSize(8));

    Iterator<Object> iter = streamedObjects.iterator();
    Object nextStreamedObject;

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.BeginXctestEvent.class));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.BeginTestSuiteEvent.class));
    XctestOutputParsing.BeginTestSuiteEvent beginTestSuiteEvent1 =
        (XctestOutputParsing.BeginTestSuiteEvent) nextStreamedObject;
    assertThat(beginTestSuiteEvent1.suite, equalTo("Example/ExampleTests.xctest(Tests)"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.BeginTestSuiteEvent.class));
    XctestOutputParsing.BeginTestSuiteEvent beginTestSuiteEvent2 =
        (XctestOutputParsing.BeginTestSuiteEvent) nextStreamedObject;
    assertThat(beginTestSuiteEvent2.suite, equalTo("SuccessTests"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.BeginTestCaseEvent.class));
    XctestOutputParsing.BeginTestCaseEvent beginTestEvent =
        (XctestOutputParsing.BeginTestCaseEvent) nextStreamedObject;
    assertThat(beginTestEvent.test, equalTo("-[SuccessTests testSuccess]"));
    assertThat(beginTestEvent.className, equalTo("SuccessTests"));
    assertThat(beginTestEvent.methodName, equalTo("testSuccess"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.EndTestCaseEvent.class));
    XctestOutputParsing.EndTestCaseEvent endTestEvent =
        (XctestOutputParsing.EndTestCaseEvent) nextStreamedObject;
    assertThat(endTestEvent.test, equalTo("-[SuccessTests testSuccess]"));
    assertThat(endTestEvent.className, equalTo("SuccessTests"));
    assertThat(endTestEvent.methodName, equalTo("testSuccess"));
    assertThat(endTestEvent.output, equalTo("-- test output --\n"));
    assertThat(endTestEvent.succeeded, is(true));
    assertThat(endTestEvent.exceptions, empty());
    assertThat(endTestEvent.totalDuration, closeTo(0.003, EPSILON));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.EndTestSuiteEvent.class));
    XctestOutputParsing.EndTestSuiteEvent endTestSuiteEvent2 =
        (XctestOutputParsing.EndTestSuiteEvent) nextStreamedObject;
    assertThat(endTestSuiteEvent2.suite, equalTo("SuccessTests"));
    assertThat(endTestSuiteEvent2.testCaseCount, equalTo(1));
    assertThat(endTestSuiteEvent2.totalFailureCount, equalTo(0));
    assertThat(endTestSuiteEvent2.unexpectedExceptionCount, equalTo(0));
    assertThat(endTestSuiteEvent2.testDuration, closeTo(0.012, EPSILON));
    assertThat(endTestSuiteEvent2.totalDuration, closeTo(0.013, EPSILON));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.EndTestSuiteEvent.class));
    XctestOutputParsing.EndTestSuiteEvent endTestSuiteEvent1 =
        (XctestOutputParsing.EndTestSuiteEvent) nextStreamedObject;
    assertThat(endTestSuiteEvent1.suite, equalTo("Example/ExampleTests.xctest(Tests)"));
    assertThat(endTestSuiteEvent1.testCaseCount, equalTo(1));
    assertThat(endTestSuiteEvent1.totalFailureCount, equalTo(0));
    assertThat(endTestSuiteEvent1.unexpectedExceptionCount, equalTo(0));
    assertThat(endTestSuiteEvent1.testDuration, closeTo(0.012, EPSILON));
    assertThat(endTestSuiteEvent1.totalDuration, closeTo(0.051, EPSILON));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.EndXctestEvent.class));
  }

  @Test
  public void streamingSimpleFailure() throws Exception {
    Path outputPath =
        TestDataHelper.getTestDataDirectory(this).resolve("xctest-output/simple-failure.txt");
    List<Object> streamedObjects = new ArrayList<>();
    try (Reader outputReader = Files.newBufferedReader(outputPath, StandardCharsets.UTF_8)) {
      XctestOutputParsing.streamOutput(
          outputReader, eventCallbackAddingEventsToList(streamedObjects));
    }
    assertThat(streamedObjects, hasSize(8));

    Iterator<Object> iter = streamedObjects.iterator();
    Object nextStreamedObject;

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.BeginXctestEvent.class));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.BeginTestSuiteEvent.class));
    XctestOutputParsing.BeginTestSuiteEvent beginTestSuiteEvent1 =
        (XctestOutputParsing.BeginTestSuiteEvent) nextStreamedObject;
    assertThat(beginTestSuiteEvent1.suite, equalTo("Example/ExampleTests.xctest(Tests)"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.BeginTestSuiteEvent.class));
    XctestOutputParsing.BeginTestSuiteEvent beginTestSuiteEvent2 =
        (XctestOutputParsing.BeginTestSuiteEvent) nextStreamedObject;
    assertThat(beginTestSuiteEvent2.suite, equalTo("FailureTests"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.BeginTestCaseEvent.class));
    XctestOutputParsing.BeginTestCaseEvent beginTestEvent =
        (XctestOutputParsing.BeginTestCaseEvent) nextStreamedObject;
    assertThat(beginTestEvent.test, equalTo("-[FailureTests testFailure]"));
    assertThat(beginTestEvent.className, equalTo("FailureTests"));
    assertThat(beginTestEvent.methodName, equalTo("testFailure"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.EndTestCaseEvent.class));
    XctestOutputParsing.EndTestCaseEvent endTestEvent =
        (XctestOutputParsing.EndTestCaseEvent) nextStreamedObject;
    assertThat(endTestEvent.totalDuration, closeTo(0.000, EPSILON));
    assertThat(endTestEvent.test, equalTo("-[FailureTests testFailure]"));
    assertThat(endTestEvent.className, equalTo("FailureTests"));
    assertThat(endTestEvent.methodName, equalTo("testFailure"));
    assertThat(endTestEvent.output, equalTo("-- test output --\n"));
    assertThat(endTestEvent.succeeded, is(false));
    assertThat(endTestEvent.exceptions, hasSize(1));

    XctestOutputParsing.TestError testException = endTestEvent.exceptions.get(0);
    assertThat(testException.lineNumber, equalTo(9));
    assertThat(testException.filePathInProject, equalTo("FailureTests.m"));
    assertThat(testException.reason, equalTo("failure reason"));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.EndTestSuiteEvent.class));
    XctestOutputParsing.EndTestSuiteEvent endTestSuiteEvent2 =
        (XctestOutputParsing.EndTestSuiteEvent) nextStreamedObject;
    assertThat(endTestSuiteEvent2.suite, equalTo("FailureTests"));
    assertThat(endTestSuiteEvent2.testCaseCount, equalTo(0));
    assertThat(endTestSuiteEvent2.totalFailureCount, equalTo(1));
    assertThat(endTestSuiteEvent2.unexpectedExceptionCount, equalTo(0));
    assertThat(endTestSuiteEvent2.testDuration, closeTo(0.012, EPSILON));
    assertThat(endTestSuiteEvent2.totalDuration, closeTo(0.013, EPSILON));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.EndTestSuiteEvent.class));
    XctestOutputParsing.EndTestSuiteEvent endTestSuiteEvent1 =
        (XctestOutputParsing.EndTestSuiteEvent) nextStreamedObject;
    assertThat(endTestSuiteEvent1.suite, equalTo("Example/ExampleTests.xctest(Tests)"));
    assertThat(endTestSuiteEvent1.testCaseCount, equalTo(0));
    assertThat(endTestSuiteEvent1.totalFailureCount, equalTo(1));
    assertThat(endTestSuiteEvent1.unexpectedExceptionCount, equalTo(0));
    assertThat(endTestSuiteEvent1.testDuration, closeTo(0.012, EPSILON));
    assertThat(endTestSuiteEvent1.totalDuration, closeTo(0.051, EPSILON));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.EndXctestEvent.class));
  }

  @Test
  public void streamingEmptyReaderDoesNotCauseFailure() {
    List<Object> streamedObjects = new ArrayList<>();
    XctestOutputParsing.streamOutput(
        new StringReader(""), eventCallbackAddingEventsToList(streamedObjects));
    assertThat(streamedObjects, hasSize(2));

    Iterator<Object> iter = streamedObjects.iterator();
    Object nextStreamedObject;

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.BeginXctestEvent.class));

    nextStreamedObject = iter.next();
    assertThat(nextStreamedObject, instanceOf(XctestOutputParsing.EndXctestEvent.class));
  }
}
