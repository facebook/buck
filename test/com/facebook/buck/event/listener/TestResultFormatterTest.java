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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

public class TestResultFormatterTest {

  private TestResultSummary successTest;
  private TestResultSummary failingTest;
  private String stackTrace;

  private TestResultFormatter createSilentFormatter() {
    return new TestResultFormatter(
        new Ansi(false),
        Verbosity.COMMANDS,
        TestResultSummaryVerbosity.of(false, false));
  }

  private TestResultFormatter createNoisyFormatter() {
    return new TestResultFormatter(
        new Ansi(false),
        Verbosity.COMMANDS,
        TestResultSummaryVerbosity.of(true, true));
  }

  @Before
  public void createTestResults() {
    stackTrace = Throwables.getStackTraceAsString(new Exception("Ouch"));

    successTest = new TestResultSummary(
        "com.example.FooTest",
        "successTest",
        ResultType.SUCCESS,
        500,
         /*message*/ null,
         /*stacktrace*/ null,
         "good stdout",
         "good stderr");

    failingTest = new TestResultSummary(
        "com.example.FooTest",
        "failTest",
        ResultType.FAILURE,
        200,
        "Unexpected fish found",
        stackTrace,
        "bad stdout",
        "bad stderr");
  }

  @Test
  public void shouldShowTargetsForTestsThatAreAboutToBeRun() {
    TestResultFormatter formatter = createSilentFormatter();
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runStarted(
        builder,
        false,
        TestSelectorList.empty(),
        false,
        ImmutableSet.of("//:example", "//foo:bar"),
        TestResultFormatter.FormatMode.BEFORE_TEST_RUN);

    assertEquals("TESTING //:example //foo:bar", toString(builder));
  }

  @Test
  public void shouldSaySelectedTestsWillBeRun() {
    TestResultFormatter formatter = createSilentFormatter();
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    TestSelectorList testSelectorList = TestSelectorList.builder()
        .addRawSelectors("com.example.clown.Car")
        .build();

    ImmutableSet<String> targetNames = ImmutableSet.of("//:example", "//foo:bar");

    formatter.runStarted(
        builder,
        false,
        testSelectorList,
        false,
        targetNames,
        TestResultFormatter.FormatMode.BEFORE_TEST_RUN);

    assertEquals("TESTING SELECTED TESTS", toString(builder));
  }

  @Test
  public void shouldExplainWhichTestsWillBeSelected() {
    TestResultFormatter formatter = createSilentFormatter();
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    TestSelectorList testSelectorList = TestSelectorList.builder()
        .addRawSelectors("com.example.clown.Car")
        .build();

    ImmutableSet<String> targetNames = ImmutableSet.of("//:example", "//foo:bar");
    boolean shouldExplain = true;

    formatter.runStarted(
        builder,
        false,
        testSelectorList,
        shouldExplain,
        targetNames,
        TestResultFormatter.FormatMode.BEFORE_TEST_RUN);

    String expected = "TESTING SELECTED TESTS\n" +
        "include class:com.example.clown.Car$ method:<any>\n" +
        "exclude everything else";

    assertEquals(
        expected,
        toString(builder));
  }

  @Test
  public void shouldShowThatAllTestAreBeingRunWhenRunIsStarted() {
    TestResultFormatter formatter = createSilentFormatter();
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    ImmutableSet<String> targetNames = ImmutableSet.of("//:example", "//foo:bar");

    formatter.runStarted(
        builder,
        true,
        TestSelectorList.empty(),
        false,
        targetNames,
        TestResultFormatter.FormatMode.BEFORE_TEST_RUN);

    assertEquals("TESTING ALL TESTS", toString(builder));
  }

  @Test
  public void shouldShowThatAllTestAreBeingRunWhenRunIsStartedWithFormatModeAfterTestRun() {
    TestResultFormatter formatter = createSilentFormatter();
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    ImmutableSet<String> targetNames = ImmutableSet.of("//:example", "//foo:bar");

    formatter.runStarted(
        builder,
        true,
        TestSelectorList.empty(),
        false,
        targetNames,
        TestResultFormatter.FormatMode.AFTER_TEST_RUN);

    assertEquals("RESULTS FOR ALL TESTS", toString(builder));
  }

  @Test
  public void shouldIndicateThatNoTestRanIfNoneRan() {
    TestResultFormatter formatter = createSilentFormatter();
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.<TestResults>of());

    assertEquals("NO TESTS RAN", toString(builder));
  }

  @Test
  public void allTestsPassingShouldBeAcknowledged() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary = new TestCaseSummary(
        "com.example.FooTest", ImmutableList.of(successTest));
    TestResults results = new TestResults(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.of(results));

    assertEquals("TESTS PASSED", toString(builder));
  }

  @Test
  public void shouldReportTheNumberOfFailingTests() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary = new TestCaseSummary(
        "com.example.FooTest", ImmutableList.of(successTest, failingTest));
    TestResults results = new TestResults(
        BuildTargetFactory.newInstance("//foo:bar"),
        ImmutableList.of(summary),
        /* contacts */ ImmutableSet.<String>of(),
        /* labels */ ImmutableSet.<String>of());
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.of(results));

    String expectedOutput = Joiner.on('\n').join(
        "TESTS FAILED: 1 FAILURE",
        "Failed target: //foo:bar",
        "FAIL com.example.FooTest");
    assertEquals(expectedOutput, toString(builder));
  }

  @Test
  public void shouldReportTheNumberOfFailingTestsWithMoreThanOneTest() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary = new TestCaseSummary(
        "com.example.FooTest",
        ImmutableList.of(
            successTest,
            failingTest,
            new TestResultSummary(
                "com.example.FooTest",
                "anotherFail",
                ResultType.FAILURE,
                200,
                "Unexpected fnord found",
                null,
                null,
                null)));
    TestResults results = new TestResults(
        BuildTargetFactory.newInstance("//foo:bar"),
        ImmutableList.of(summary),
        /* contacts */ ImmutableSet.<String>of(),
        /* labels */ ImmutableSet.<String>of());
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.of(results));

    String expectedOutput = Joiner.on('\n').join(
        "TESTS FAILED: 2 FAILURES",
        "Failed target: //foo:bar",
        "FAIL com.example.FooTest");
    assertEquals(expectedOutput, toString(builder));
  }

  @Test
  public void shouldReportMinimalInformationForAPassingTest() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary = new TestCaseSummary(
        "com.example.FooTest", ImmutableList.of(successTest));
    TestResults results = new TestResults(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    assertEquals("PASS     500ms  1 Passed   0 Skipped   0 Failed   com.example.FooTest",
        toString(builder));
  }

  @Test
  public void shouldOutputStackTraceStdOutAndStdErrOfFailingTest() {
    TestResultFormatter formatter = createNoisyFormatter();
    TestCaseSummary summary = new TestCaseSummary(
        "com.example.FooTest", ImmutableList.of(failingTest));
    TestResults results = new TestResults(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    String expected = String.format(Joiner.on('\n').join(
        "FAIL     200ms  0 Passed   0 Skipped   1 Failed   com.example.FooTest",
        "FAILURE %s %s: %s",
        "%s",
        "====STANDARD OUT====",
        "%s",
        "====STANDARD ERR====",
        "%s"),
        failingTest.getTestCaseName(),
        failingTest.getTestName(),
        failingTest.getMessage(),
        stackTrace,
        failingTest.getStdOut(),
        failingTest.getStdErr());

    assertEquals(expected, toString(builder));
  }

  @Test
  public void shouldNotOutputStackTraceStdOutAndStdErrOfFailingTest() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary = new TestCaseSummary(
        "com.example.FooTest", ImmutableList.of(failingTest));
    TestResults results = new TestResults(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    String expected = String.format(Joiner.on('\n').join(
            "FAIL     200ms  0 Passed   0 Skipped   1 Failed   com.example.FooTest",
            "FAILURE %s %s: %s",
            "%s"),
        failingTest.getTestCaseName(),
        failingTest.getTestName(),
        failingTest.getMessage(),
        stackTrace);

    assertEquals(expected, toString(builder));
  }

  private String toString(ImmutableList.Builder<String> builder) {
    return Joiner.on('\n').join(builder.build());
  }
}
