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

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.test.FakeTestResults;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.test.config.TestResultSummaryVerbosity;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.logging.Level;
import org.junit.Before;
import org.junit.Test;

public class TestResultFormatterTest {

  private TestResultSummary successTest;
  private TestResultSummary failingTest;
  private String stackTrace;
  private FileSystem vfs;
  private Path logPath;

  private TestResultFormatter createSilentFormatter() {
    return new TestResultFormatter(
        new Ansi(false),
        Verbosity.COMMANDS,
        TestResultSummaryVerbosity.of(false, false),
        Locale.US,
        Optional.of(logPath),
        TimeZone.getTimeZone("America/Los_Angeles"));
  }

  private TestResultFormatter createNoisyFormatter() {
    return new TestResultFormatter(
        new Ansi(false),
        Verbosity.COMMANDS,
        TestResultSummaryVerbosity.of(true, true),
        Locale.US,
        Optional.of(logPath),
        TimeZone.getTimeZone("America/Los_Angeles"));
  }

  private TestResultFormatter createFormatterWithMaxLogLines(int logLines) {
    return new TestResultFormatter(
        new Ansi(false),
        Verbosity.COMMANDS,
        TestResultSummaryVerbosity.builder()
            .setIncludeStdOut(false)
            .setIncludeStdErr(false)
            .setMaxDebugLogLines(logLines)
            .build(),
        Locale.US,
        Optional.of(logPath),
        TimeZone.getTimeZone("America/Los_Angeles"));
  }

  @Before
  public void createTestLogFile() {
    vfs = Jimfs.newFileSystem(Configuration.unix());
    logPath = vfs.getPath("log.txt");
  }

  @Before
  public void createTestResults() {
    stackTrace = Throwables.getStackTraceAsString(new Exception("Ouch"));

    successTest =
        new TestResultSummary(
            "com.example.FooTest",
            "successTest",
            ResultType.SUCCESS,
            500,
            /*message*/ null,
            /*stacktrace*/ null,
            "good stdout",
            "good stderr");

    failingTest =
        new TestResultSummary(
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

    TestSelectorList testSelectorList =
        TestSelectorList.builder().addRawSelectors("com.example.clown.Car").build();

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

    TestSelectorList testSelectorList =
        TestSelectorList.builder().addRawSelectors("com.example.clown.Car").build();

    ImmutableSet<String> targetNames = ImmutableSet.of("//:example", "//foo:bar");
    boolean shouldExplain = true;

    formatter.runStarted(
        builder,
        false,
        testSelectorList,
        shouldExplain,
        targetNames,
        TestResultFormatter.FormatMode.BEFORE_TEST_RUN);

    String expected =
        "TESTING SELECTED TESTS\n"
            + "include class:com.example.clown.Car$ method:<any>\n"
            + "exclude everything else";

    assertEquals(expected, toString(builder));
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

    formatter.runComplete(builder, ImmutableList.of(), ImmutableList.of());

    assertThat(toString(builder), containsString("NO TESTS RAN"));
  }

  @Test
  public void shouldIndicateThatNoTestRanIfNoneRanAndUsingFilter() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary = new TestCaseSummary("com.example.FooTest", ImmutableList.of());
    TestResults results = FakeTestResults.of(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.of(results), ImmutableList.of());

    assertThat(toString(builder), containsString("NO TESTS RAN"));
  }

  @Test
  public void allTestsPassingShouldBeAcknowledged() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(successTest));
    TestResults results = FakeTestResults.of(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.of(results), ImmutableList.of());

    assertEquals("TESTS PASSED", toString(builder));
  }

  @Test
  public void allTestsPassingShouldIncludeNonEmptyTestLogs() throws IOException {
    Path testLogPath = vfs.getPath("test-log.txt");
    Files.write(testLogPath, ImmutableList.of("Hello world"), StandardCharsets.UTF_8);
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(successTest));
    TestResults results =
        FakeTestResults.withTestLogs(ImmutableList.of(summary), ImmutableList.of(testLogPath));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.of(results), ImmutableList.of());

    assertEquals("Updated test logs: log.txt\nTESTS PASSED", toString(builder));
  }

  @Test
  public void allTestsPassingShouldNotEmptyTestLogs() throws IOException {
    Path testLogPath = vfs.getPath("test-log.txt");
    Files.write(testLogPath, new byte[0]);
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(successTest));
    TestResults results =
        FakeTestResults.withTestLogs(ImmutableList.of(summary), ImmutableList.of(testLogPath));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.of(results), ImmutableList.of());

    assertEquals("TESTS PASSED", toString(builder));
  }

  @Test
  public void allTestsPassingShouldNotShowTestStatusMessages() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(successTest));
    TestResults results = FakeTestResults.of(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(
        builder,
        ImmutableList.of(results),
        ImmutableList.of(TestStatusMessage.of("Hello world", Level.INFO, 12345L)));

    assertEquals("TESTS PASSED", toString(builder));
  }

  @Test
  public void shouldReportTheNumberOfFailingTests() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(successTest, failingTest));
    TestResults results =
        TestResults.of(
            BuildTargetFactory.newInstance("//foo:bar"),
            ImmutableList.of(summary),
            /* contacts */ ImmutableSet.of(),
            /* labels */ ImmutableSet.of());
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.of(results), ImmutableList.of());

    String expectedOutput =
        Joiner.on('\n')
            .join(
                "TESTS FAILED: 1 FAILURE", "Failed target: //foo:bar", "FAIL com.example.FooTest");
    assertEquals(expectedOutput, toString(builder));
  }

  @Test
  public void failingTestShouldShowTestStatusMessages() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(successTest, failingTest));
    TestResults results =
        TestResults.of(
            BuildTargetFactory.newInstance("//foo:bar"),
            ImmutableList.of(summary),
            /* contacts */ ImmutableSet.of(),
            /* labels */ ImmutableSet.of());
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(
        builder,
        ImmutableList.of(results),
        ImmutableList.of(TestStatusMessage.of("Hello world", Level.INFO, 1450473060000L)));

    String expectedOutput =
        Joiner.on('\n')
            .join(
                "====TEST STATUS MESSAGES====",
                "[2015-12-18 13:11:00.000][INFO] Hello world",
                "TESTS FAILED: 1 FAILURE",
                "Failed target: //foo:bar",
                "FAIL com.example.FooTest");
    assertEquals(expectedOutput, toString(builder));
  }

  @Test
  public void shouldReportTheNumberOfFailingTestsWithMoreThanOneTest() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary =
        new TestCaseSummary(
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
    TestResults results =
        TestResults.of(
            BuildTargetFactory.newInstance("//foo:bar"),
            ImmutableList.of(summary),
            /* contacts */ ImmutableSet.of(),
            /* labels */ ImmutableSet.of());
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.runComplete(builder, ImmutableList.of(results), ImmutableList.of());

    String expectedOutput =
        Joiner.on('\n')
            .join(
                "TESTS FAILED: 2 FAILURES", "Failed target: //foo:bar", "FAIL com.example.FooTest");
    assertEquals(expectedOutput, toString(builder));
  }

  @Test
  public void shouldReportMinimalInformationForAPassingTest() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(successTest));
    TestResults results = FakeTestResults.of(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    assertEquals(
        "PASS     500ms  1 Passed   0 Skipped   0 Failed   com.example.FooTest", toString(builder));
  }

  @Test
  public void shouldUseDecimalCommaForGerman() {
    TestResultFormatter formatter =
        new TestResultFormatter(
            new Ansi(false),
            Verbosity.COMMANDS,
            TestResultSummaryVerbosity.of(false, false),
            Locale.GERMAN,
            Optional.of(logPath),
            TimeZone.getTimeZone("America/Los_Angeles"));

    TestCaseSummary summary =
        new TestCaseSummary(
            "com.example.FooTest",
            ImmutableList.of(
                new TestResultSummary(
                    "com.example.FooTest",
                    "successTest",
                    ResultType.SUCCESS,
                    12300,
                    /*message*/ null,
                    /*stacktrace*/ null,
                    "good stdout",
                    "good stderr")));
    TestResults results = FakeTestResults.of(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    assertEquals(
        "PASS     12,3s  1 Passed   0 Skipped   0 Failed   com.example.FooTest", toString(builder));
  }

  @Test
  public void shouldOutputStackTraceStdOutAndStdErrOfFailingTest() {
    TestResultFormatter formatter = createNoisyFormatter();
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(failingTest));
    TestResults results = FakeTestResults.of(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    String expected =
        String.format(
            Joiner.on('\n')
                .join(
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
  public void shouldNotOutputLogLinesOfFailingTestWhenLogIsEmpty() throws IOException {
    TestResultFormatter formatter = createFormatterWithMaxLogLines(10);
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(failingTest));
    Files.write(logPath, new byte[0]);

    TestResults results =
        TestResults.builder()
            .setBuildTarget(BuildTargetFactory.newInstance("//foo:bar"))
            .setTestCases(ImmutableList.of(summary))
            .addTestLogPaths(logPath)
            .build();

    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    String expected =
        String.format(
            Joiner.on('\n')
                .join(
                    "FAIL     200ms  0 Passed   0 Skipped   1 Failed   com.example.FooTest",
                    "FAILURE %s %s: %s",
                    "%s"),
            failingTest.getTestCaseName(),
            failingTest.getTestName(),
            failingTest.getMessage(),
            stackTrace);

    assertEquals(expected, toString(builder));
  }

  @Test
  public void shouldOutputLogLinesOfFailingTest() throws IOException {
    TestResultFormatter formatter = createFormatterWithMaxLogLines(10);
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(failingTest));
    Files.write(
        logPath,
        ImmutableList.of("This is a debug log", "Here's another one"),
        StandardCharsets.UTF_8);

    TestResults results =
        TestResults.builder()
            .setBuildTarget(BuildTargetFactory.newInstance("//foo:bar"))
            .setTestCases(ImmutableList.of(summary))
            .addTestLogPaths(logPath)
            .build();

    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    String expected =
        String.format(
            Joiner.on('\n')
                .join(
                    "FAIL     200ms  0 Passed   0 Skipped   1 Failed   com.example.FooTest",
                    "FAILURE %s %s: %s",
                    "%s",
                    "====TEST LOGS====",
                    "Logs from log.txt:",
                    "This is a debug log",
                    "Here's another one"),
            failingTest.getTestCaseName(),
            failingTest.getTestName(),
            failingTest.getMessage(),
            stackTrace);

    assertEquals(expected, toString(builder));
  }

  @Test
  public void shouldNotOutputLogPathInlineForPassingTest() throws IOException {
    TestResultFormatter formatter = createFormatterWithMaxLogLines(10);
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(successTest));
    Files.write(
        logPath,
        ImmutableList.of("This is a debug log", "Here's another one"),
        StandardCharsets.UTF_8);

    TestResults results =
        TestResults.builder()
            .setBuildTarget(BuildTargetFactory.newInstance("//foo:bar"))
            .setTestCases(ImmutableList.of(summary))
            .addTestLogPaths(logPath)
            .build();

    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    assertEquals(
        "PASS     500ms  1 Passed   0 Skipped   0 Failed   com.example.FooTest", toString(builder));
  }

  @Test
  public void shouldOutputTruncatedLogLinesOfFailingTest() throws IOException {
    TestResultFormatter formatter = createFormatterWithMaxLogLines(3);
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(failingTest));
    Files.write(
        logPath,
        ImmutableList.of("This log won't appear", "This one will", "Another one", "Should be last"),
        StandardCharsets.UTF_8);

    TestResults results =
        TestResults.builder()
            .setBuildTarget(BuildTargetFactory.newInstance("//foo:bar"))
            .setTestCases(ImmutableList.of(summary))
            .addTestLogPaths(logPath)
            .build();

    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    String expected =
        String.format(
            Joiner.on('\n')
                .join(
                    "FAIL     200ms  0 Passed   0 Skipped   1 Failed   com.example.FooTest",
                    "FAILURE %s %s: %s",
                    "%s",
                    "====TEST LOGS====",
                    "Last 3 test log lines from log.txt:",
                    "This one will",
                    "Another one",
                    "Should be last"),
            failingTest.getTestCaseName(),
            failingTest.getTestName(),
            failingTest.getMessage(),
            stackTrace);

    assertEquals(expected, toString(builder));
  }

  @Test
  public void shouldNotOutputLogLinesOfFailingTestIfMaxLinesIsZero() throws IOException {
    TestResultFormatter formatter = createFormatterWithMaxLogLines(0);
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(failingTest));
    Files.write(
        logPath, ImmutableList.of("None", "of", "these", "will", "appear"), StandardCharsets.UTF_8);

    TestResults results =
        TestResults.builder()
            .setBuildTarget(BuildTargetFactory.newInstance("//foo:bar"))
            .setTestCases(ImmutableList.of(summary))
            .addTestLogPaths(logPath)
            .build();

    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    String expected =
        String.format(
            Joiner.on('\n')
                .join(
                    "FAIL     200ms  0 Passed   0 Skipped   1 Failed   com.example.FooTest",
                    "FAILURE %s %s: %s",
                    "%s"),
            failingTest.getTestCaseName(),
            failingTest.getTestName(),
            failingTest.getMessage(),
            stackTrace);

    assertEquals(expected, toString(builder));
  }

  @Test
  public void shouldNotOutputStackTraceStdOutAndStdErrOfFailingTest() {
    TestResultFormatter formatter = createSilentFormatter();
    TestCaseSummary summary =
        new TestCaseSummary("com.example.FooTest", ImmutableList.of(failingTest));
    TestResults results = FakeTestResults.of(ImmutableList.of(summary));
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    formatter.reportResult(builder, results);

    String expected =
        String.format(
            Joiner.on('\n')
                .join(
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
