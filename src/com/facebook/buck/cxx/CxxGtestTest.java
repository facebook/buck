/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.ForwardingBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.ChunkAccumulator;
import com.facebook.buck.util.xml.XmlDomParser;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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

  private final BuildRule binary;
  private final long maxTestOutputSize;

  public CxxGtestTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRule binary,
      Tool executable,
      ImmutableMap<String, String> env,
      Supplier<ImmutableList<String>> args,
      ImmutableSortedSet<? extends SourcePath> resources,
      ImmutableSet<SourcePath> additionalCoverageTargets,
      Supplier<ImmutableSortedSet<BuildRule>> additionalDeps,
      ImmutableSet<String> labels,
      ImmutableSet<String> contacts,
      boolean runTestSeparately,
      Optional<Long> testRuleTimeoutMs,
      long maxTestOutputSize) {
    super(
        buildTarget,
        projectFilesystem,
        params,
        executable,
        env,
        args,
        resources,
        additionalCoverageTargets,
        additionalDeps,
        labels,
        contacts,
        runTestSeparately,
        testRuleTimeoutMs);
    this.binary = binary;
    this.maxTestOutputSize = maxTestOutputSize;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(
        getBuildTarget(), Preconditions.checkNotNull(binary.getSourcePathToOutput()));
  }

  @Override
  protected ImmutableList<String> getShellCommand(SourcePathResolver pathResolver, Path output) {
    return ImmutableList.<String>builder()
        .addAll(getExecutableCommand().getCommandPrefix(pathResolver))
        .add("--gtest_color=no")
        .add("--gtest_output=xml:" + getProjectFilesystem().resolve(output))
        .build();
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
          Preconditions.checkNotNull(stdout.get(currentTest.get())).append(line);
        }
      }
    }

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

      // Prepare the tests stdout.
      String testStdout = "";
      if (stdout.containsKey(testFull)) {
        testStdout = Joiner.on(System.lineSeparator()).join(stdout.get(testFull).getChunks());
      }

      summariesBuilder.add(
          new TestResultSummary(
              testCase, testName, type, time.longValue(), message, "", testStdout, ""));
    }

    return summariesBuilder.build();
  }

  // The C++ test rules just wrap a test binary produced by another rule, so make sure that's
  // always available to run the test.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return Stream.concat(
        super.getRuntimeDeps(ruleFinder),
        BuildableSupport.getDepsCollection(getExecutableCommand(), ruleFinder)
            .stream()
            .map(BuildRule::getBuildTarget));
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {
    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("gtest")
        .addAllCommand(
            getExecutableCommand().getCommandPrefix(buildContext.getSourcePathResolver()))
        .addAllCommand(getArgs().get())
        .putAllEnv(getEnv(buildContext.getSourcePathResolver()))
        .addAllLabels(getLabels())
        .addAllContacts(getContacts())
        .addAllAdditionalCoverageTargets(
            buildContext
                .getSourcePathResolver()
                .getAllAbsolutePaths(getAdditionalCoverageTargets()))
        .build();
  }
}
