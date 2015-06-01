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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.hamcrest.Matcher;
import org.junit.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class XctoolOutputParsingTest {

  @Test
  public void noTestCasesAndOcunitFailureReturnsFailedTestResultSummary() throws Exception {
    Path jsonPath = TestDataHelper.getTestDataDirectory(this)
        .resolve("xctool-output/ocunit-failure.json");
    try (Reader jsonReader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
      List<TestCaseSummary> summaries = XctoolOutputParsing.parseOutputFromReader(jsonReader);
      assertThat(summaries, hasSize(1));
      Matcher<TestResultSummary> summaryMatcher =
          allOf(
              hasProperty(
                  "type",
                  equalTo(ResultType.FAILURE)),
              hasProperty(
                  "message",
                  containsString(
                      "dyld: app was built for iOS 8.3 which is newer than this simulator 8.1")));
      assertThat(
          summaries.get(0).getTestResults(),
          contains(summaryMatcher));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void mixedPassAndFailReturnsMixedResultSummary() throws Exception {
    Path jsonPath = TestDataHelper.getTestDataDirectory(this)
        .resolve("xctool-output/mixed-pass-and-fail.json");
    try (Reader jsonReader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
      List<TestCaseSummary> summaries = XctoolOutputParsing.parseOutputFromReader(jsonReader);
      assertThat(summaries, hasSize(2));

      Matcher<TestResultSummary> isOtherTestsTestSomethingSuccess =
          allOf(
              hasProperty("testCaseName", equalTo("OtherTests")),
              hasProperty("testName", equalTo("-[OtherTests testSomething]")),
              hasProperty("type", equalTo(ResultType.SUCCESS)),
              hasProperty("time", equalTo(3L)),
              hasProperty("message", nullValue(String.class)),
              hasProperty("stacktrace", nullValue(String.class)),
              hasProperty("stdOut", nullValue(String.class)),
              hasProperty("stdErr", nullValue(String.class)));

      List<TestResultSummary> otherTestsResults = summaries.get(0).getTestResults();
      assertThat(otherTestsResults, contains(isOtherTestsTestSomethingSuccess));

      Matcher<TestResultSummary> isSomeTestsTestBacktraceOutputIsCaptured =
          allOf(
              hasProperty("testCaseName", equalTo("SomeTests")),
              hasProperty("testName", equalTo("-[SomeTests testBacktraceOutputIsCaptured]")),
              hasProperty("type", equalTo(ResultType.SUCCESS)),
              hasProperty("time", equalTo(0L)),
              hasProperty("message", nullValue(String.class)),
              hasProperty("stacktrace", nullValue(String.class)),
              hasProperty("stdOut", containsString("-[SenTestCase performTest:]")),
              hasProperty("stdErr", nullValue(String.class)));

      Matcher<TestResultSummary> isSomeTestsTestOutputMerging =
          allOf(
              hasProperty("testCaseName", equalTo("SomeTests")),
              hasProperty("testName", equalTo("-[SomeTests testOutputMerging]")),
              hasProperty("type", equalTo(ResultType.SUCCESS)),
              hasProperty("time", equalTo(0L)),
              hasProperty("message", nullValue(String.class)),
              hasProperty("stacktrace", nullValue(String.class)),
              hasProperty("stdOut", containsString("stdout-line1\nstderr-line1\n")),
              hasProperty("stdErr", nullValue(String.class)));

      Matcher<TestResultSummary> isSomeTestsTestPrintSDK =
          allOf(
              hasProperty("testCaseName", equalTo("SomeTests")),
              hasProperty("testName", equalTo("-[SomeTests testPrintSDK]")),
              hasProperty("type", equalTo(ResultType.SUCCESS)),
              hasProperty("time", equalTo(1L)),
              hasProperty("message", nullValue(String.class)),
              hasProperty("stacktrace", nullValue(String.class)),
              hasProperty("stdOut", containsString("SDK: 6.1")),
              hasProperty("stdErr", nullValue(String.class)));

      Matcher<TestResultSummary> isSomeTestsTestStream =
          allOf(
              hasProperty("testCaseName", equalTo("SomeTests")),
              hasProperty("testName", equalTo("-[SomeTests testStream]")),
              hasProperty("type", equalTo(ResultType.SUCCESS)),
              hasProperty("time", equalTo(754L)),
              hasProperty("message", nullValue(String.class)),
              hasProperty("stacktrace", nullValue(String.class)),
              hasProperty("stdOut", containsString(">>>> i = 0")),
              hasProperty("stdErr", nullValue(String.class)));

      Matcher<TestResultSummary> isSomeTestsTestWillFail =
          allOf(
              hasProperty("testCaseName", equalTo("SomeTests")),
              hasProperty("testName", equalTo("-[SomeTests testWillFail]")),
              hasProperty("type", equalTo(ResultType.FAILURE)),
              hasProperty("time", equalTo(0L)),
              hasProperty(
                  "message",
                  containsString("SomeTests.m:40: 'a' should be equal to 'b'")),
              hasProperty("stacktrace", nullValue(String.class)),
              hasProperty("stdOut", nullValue(String.class)),
              hasProperty("stdErr", nullValue(String.class)));

      Matcher<TestResultSummary> isSomeTestsTestWillPass =
          allOf(
              hasProperty("testCaseName", equalTo("SomeTests")),
              hasProperty("testName", equalTo("-[SomeTests testWillPass]")),
              hasProperty("type", equalTo(ResultType.SUCCESS)),
              hasProperty("time", equalTo(0L)),
              hasProperty("message", nullValue(String.class)),
              hasProperty("stacktrace", nullValue(String.class)),
              hasProperty("stdOut", nullValue(String.class)),
              hasProperty("stdErr", nullValue(String.class)));

      List<TestResultSummary> someTestsResults = summaries.get(1).getTestResults();
      assertThat(
          someTestsResults,
          contains(
              isSomeTestsTestBacktraceOutputIsCaptured,
              isSomeTestsTestOutputMerging,
              isSomeTestsTestPrintSDK,
              isSomeTestsTestStream,
              isSomeTestsTestWillFail,
              isSomeTestsTestWillPass));
    }
  }
}
