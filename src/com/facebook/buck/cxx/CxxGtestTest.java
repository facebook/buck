/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.ChunkAccumulator;
import com.facebook.buck.util.xml.XmlDomParser;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Files;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
class CxxGtestTest extends CxxTest implements HasRuntimeDeps, ExternalTestRunnerRule {
  private static final Pattern START = Pattern.compile("^\\[\\s*RUN\\s*\\] (.*)$");
  private static final Pattern END = Pattern.compile("^\\[\\s*(FAILED|OK)\\s*\\] .*");
  private static final String NOTRUN = "notrun";
  private static final String TEST_MARKER = "  ";
  private static final String TEST_LIST = "testList";

  private final long maxTestOutputSize;
  private final boolean checkGTestTestList;

  public CxxGtestTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRule binary,
      Tool executable,
      ImmutableMap<String, Arg> env,
      ImmutableList<Arg> args,
      ImmutableSortedSet<? extends SourcePath> resources,
      ImmutableSet<SourcePath> additionalCoverageTargets,
      Function<SourcePathRuleFinder, ImmutableSortedSet<BuildRule>> additionalDeps,
      ImmutableSet<String> labels,
      ImmutableSet<String> contacts,
      boolean runTestSeparately,
      Optional<Long> testRuleTimeoutMs,
      long maxTestOutputSize,
      boolean checkGTestTestList) {
    super(
        buildTarget,
        projectFilesystem,
        params,
        binary,
        executable,
        env,
        args,
        resources,
        additionalCoverageTargets,
        additionalDeps,
        labels,
        contacts,
        runTestSeparately,
        testRuleTimeoutMs,
        CxxTestType.GTEST);
    this.maxTestOutputSize = maxTestOutputSize;
    this.checkGTestTestList = checkGTestTestList;
  }

  @Override
  protected ImmutableList<String> getShellCommand(
      SourcePathResolverAdapter pathResolver, Path output) {
    return ImmutableList.<String>builder()
        .addAll(getExecutableCommand(OutputLabel.defaultLabel()).getCommandPrefix(pathResolver))
        .add("--gtest_color=no")
        .add("--gtest_output=xml:" + getProjectFilesystem().resolve(output))
        .build();
  }

  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      BuildContext buildContext,
      TestReportingCallback testReportingCallback) {

    ImmutableList.Builder<Step> builder =
        ImmutableList.<Step>builder()
            .addAll(super.runTests(executionContext, options, buildContext, testReportingCallback));
    if (checkGTestTestList) {
      builder.add(
          new WriteTestListToFileStep(
              getExecutableCommand(OutputLabel.defaultLabel())
                  .getCommandPrefix(buildContext.getSourcePathResolver()),
              buildContext.getBuildCellRootPath()));
    }
    return builder.build();
  }

  private Path getPathToTestList() {
    return getPathToTestOutputDirectory().resolve(TEST_LIST);
  }

  private TestResultSummary getProgramFailureSummary(String message, String output) {
    return new TestResultSummary(
        getBuildTarget().toString(), "main", ResultType.FAILURE, 0L, message, "", output, "");
  }

  @Override
  protected ImmutableList<TestResultSummary> parseResults(Path exitCode, Path output, Path results)
      throws IOException {

    // Make sure we didn't die abnormally
    Optional<String> abnormalExitError = validateExitCode(getProjectFilesystem().resolve(exitCode));
    if (abnormalExitError.isPresent()) {
      return ImmutableList.of(
          getProgramFailureSummary(
              abnormalExitError.get(),
              getProjectFilesystem().readFileIfItExists(output).orElse("")));
    }

    // Try to parse the results file first, which should be written if the test suite exited
    // normally (even in the event of a failing test).  If this fails, just construct a test
    // summary with the output we have.
    Document doc;
    try {
      doc = XmlDomParser.parse(getProjectFilesystem().resolve(results));
    } catch (SAXException e) {
      return ImmutableList.of(
          getProgramFailureSummary(
              "test program aborted before finishing",
              getProjectFilesystem().readFileIfItExists(output).orElse("")));
    }

    ImmutableList.Builder<TestResultSummary> summariesBuilder = ImmutableList.builder();

    // It's possible the test output had invalid characters in it's output, so make sure to
    // ignore these as we parse the output lines.
    Optional<String> currentTest = Optional.empty();
    Map<String, ChunkAccumulator> stdout = new HashMap<>();
    CharsetDecoder decoder = Charsets.UTF_8.newDecoder();
    decoder.onMalformedInput(CodingErrorAction.IGNORE);

    try (InputStream input = getProjectFilesystem().newFileInputStream(output);
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher matcher;
        if ((matcher = START.matcher(line.trim())).matches()) {
          String test = matcher.group(1);
          currentTest = Optional.of(test);
          stdout.put(test, new ChunkAccumulator(Charsets.UTF_8, maxTestOutputSize));
        } else if (END.matcher(line.trim()).matches()) {
          currentTest = Optional.empty();
        } else if (currentTest.isPresent()) {
          Objects.requireNonNull(stdout.get(currentTest.get())).append(line);
        }
      }
    }

    Set<String> seenTests = new HashSet<>();
    NodeList testcases = doc.getElementsByTagName("testcase");
    for (int index = 0; index < testcases.getLength(); index++) {
      Node testcase = testcases.item(index);
      NamedNodeMap attributes = testcase.getAttributes();
      String testCase = attributes.getNamedItem("classname").getNodeValue();
      String testName = attributes.getNamedItem("name").getNodeValue();
      String testFull = String.format("%s.%s", testCase, testName);
      Double time = Double.parseDouble(attributes.getNamedItem("time").getNodeValue()) * 1000;

      // Prepare the result message and type
      ResultType type = ResultType.SUCCESS;
      String message = "";
      if (testcase.getChildNodes().getLength() > 0) {
        Node failure = testcase.getChildNodes().item(1);
        type = ResultType.FAILURE;
        message = failure.getAttributes().getNamedItem("message").getNodeValue();
      } else if (attributes.getNamedItem("status").getNodeValue().equals(NOTRUN)) {
        type = ResultType.ASSUMPTION_VIOLATION;
        message = "DISABLED";
      }

      seenTests.add(testFull);

      // Prepare the tests stdout.
      String testStdout = "";
      if (stdout.containsKey(testFull)) {
        testStdout = Joiner.on(System.lineSeparator()).join(stdout.get(testFull).getChunks());
      }

      summariesBuilder.add(
          new TestResultSummary(
              testCase, testName, type, time.longValue(), message, "", testStdout, ""));
    }

    if (checkGTestTestList) {
      summariesBuilder.addAll(
          reportNotSeenTests(
              getProjectFilesystem().resolve(output.resolveSibling(TEST_LIST)), seenTests));
    }

    return summariesBuilder.build();
  }

  private Iterable<TestResultSummary> reportNotSeenTests(Path testListPath, Set<String> seenTests)
      throws IOException {

    ImmutableList.Builder<TestResultSummary> listBuilder = ImmutableList.builder();
    List<String> lines = Files.readLines(testListPath.toFile(), Charsets.UTF_8);
    Optional<String> prefix = Optional.empty();
    for (String line : lines) {
      if (line.startsWith(TEST_MARKER)) {
        if (!prefix.isPresent()) {
          throw new IllegalStateException("Test name without prefix before it");
        }
        String testCaseName = line.substring(TEST_MARKER.length()).trim();
        String name = prefix.get() + "." + testCaseName;

        if (!seenTests.contains(name)) {
          listBuilder.add(reportNotExecutedTest(testCaseName, prefix.get()));
        }
      } else {
        if (!line.endsWith(".")) {
          throw new IllegalStateException(
              "Malformed test name. '" + line + "'. It should end with a dot.");
        }
        prefix = Optional.of(line.substring(0, line.length() - 1));
      }
    }
    return listBuilder.build();
  }

  @Nonnull
  private TestResultSummary reportNotExecutedTest(String testCaseName, String testName) {
    return new TestResultSummary(
        testCaseName, testName, ResultType.FAILURE, 0, "Test wasn't run", "", "", "");
  }

  private class WriteTestListToFileStep extends ShellStep {
    private ImmutableList<String> command;

    public WriteTestListToFileStep(ImmutableList<String> testCommand, Path workingDirectory) {
      super(workingDirectory);
      this.command = testCommand;
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      return ImmutableList.<String>builder().addAll(command).add("--gtest_list_tests").build();
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {

      StepExecutionResult result = super.execute(context);
      if (!result.isSuccess()) {
        return result;
      }

      // DefaultProcessExecutor used in the super class adds ANSI codes to stdout
      // there's no easy way to disable this behavior, so we have to clean it up here
      String testsList = removeANSI(getStdout());
      getProjectFilesystem().writeContentsToPath(testsList, getPathToTestList());

      return StepExecutionResults.SUCCESS;
    }

    private String removeANSI(String text) {
      return text.replaceAll("\u001b\\[\\d+m", "");
    }

    @Override
    public String getShortName() {
      return "Write Test List To A File";
    }
  }
}
