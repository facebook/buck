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
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Ansi;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

public class TestResultFormatterTest {

  private TestResultFormatter formatter;
  private TestResultSummary successTest;
  private TestResultSummary failingTest;
  private String stackTrace;

  @Before
  public void createFormatter() {
    formatter = new TestResultFormatter(
        new Ansi(false),
        /* isAnAssumptionViolationAnError) */ false);
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
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runStarted(
        builder,
        false,
        TestSelectorList.empty(),
        false,
        ImmutableSet.of("//:example", "//foo:bar"));

    assertEquals("TESTING //:example //foo:bar", toString(builder));
  }

  @Test
  public void shouldSaySelectedTestsWillBeRun() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    TestSelectorList testSelectorList = TestSelectorList.builder()
        .addRawSelectors("com.example.clown.Car")
        .build();

    ImmutableSet<String> targetNames = ImmutableSet.of("//:example", "//foo:bar");

    formatter.runStarted(builder, false, testSelectorList, false, targetNames);

    assertEquals("TESTING SELECTED TESTS", toString(builder));
  }

  @Test
  public void shouldExplainWhichTestsWillBeSelected() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    TestSelectorList testSelectorList = TestSelectorList.builder()
        .addRawSelectors("com.example.clown.Car")
        .build();

    ImmutableSet<String> targetNames = ImmutableSet.of("//:example", "//foo:bar");
    boolean shouldExplain = true;

    formatter.runStarted(builder, false, testSelectorList, shouldExplain, targetNames);

    String expected = "TESTING SELECTED TESTS\n" +
        "include class:com.example.clown.Car$ method:<any>\n" +
        "exclude everything else";

    assertEquals(
        expected,
        toString(builder));
  }

  @Test
  public void shouldShowThatAllTestAreBeingRunWhenRunIsStarted() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    ImmutableSet<String> targetNames = ImmutableSet.of("//:example", "//foo:bar");

    formatter.runStarted(builder, true, TestSelectorList.empty(), false, targetNames);

    assertEquals("TESTING ALL TESTS", toString(builder));
  }

  @Test
  public void shouldIndicateThatNoTestRanIfNoneRan() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.<TestResults>of());

    assertEquals("NO TESTS RAN", toString(builder));
  }

  @Test
  public void allTestsPassingShouldBeAcknowledged() {
    TestCaseSummary summary = new TestCaseSummary(
        "com.example.FooTest", ImmutableList.of(successTest));
    TestResults results = new TestResults(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.of(results));

    assertEquals("TESTS PASSED", toString(builder));
  }

  @Test
  public void shouldReportTheNumberOfFailingTests() {
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
        "TESTS FAILED: 1 Failures",
        "Failed target: //foo:bar",
        "FAIL com.example.FooTest");
    assertEquals(expectedOutput, toString(builder));
  }

  @Test
  public void shouldReportMinimalInformationForAPassingTest() {
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
    TestCaseSummary summary = new TestCaseSummary(
        "com.example.FooTest", ImmutableList.of(failingTest));
    TestResults results = new TestResults(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    String expected = String.format(Joiner.on('\n').join(
        "FAIL     200ms  0 Passed   0 Skipped   1 Failed   com.example.FooTest",
        "FAILURE %s: %s",
        "%s",
        "====STANDARD OUT====",
        "%s",
        "====STANDARD ERR====",
        "%s"),
        failingTest.getTestName(),
        failingTest.getMessage(),
        stackTrace,
        failingTest.getStdOut(),
        failingTest.getStdErr());

    assertEquals(expected, toString(builder));
  }

  private String toString(ImmutableList.Builder<String> builder) {
    return Joiner.on('\n').join(builder.build());
  }
}
