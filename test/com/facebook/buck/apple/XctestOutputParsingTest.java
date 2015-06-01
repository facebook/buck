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

public class XctestOutputParsingTest {

  @Test
  @SuppressWarnings("unchecked")
  public void mixedPassAndFailReturnsMixedResultSummary() throws Exception {
    Path outputPath = TestDataHelper.getTestDataDirectory(this)
        .resolve("xctest-output/mixed-pass-and-fail.txt");
    try (Reader outputReader = Files.newBufferedReader(outputPath, StandardCharsets.UTF_8)) {
      List<TestCaseSummary> summaries = XctestOutputParsing.parseOutputFromReader(outputReader);
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
      assertThat(
          someTestsResults,
          contains(
              isSomeTestsTestWillFail,
              isSomeTestsTestWillPass));
    }
  }
}
