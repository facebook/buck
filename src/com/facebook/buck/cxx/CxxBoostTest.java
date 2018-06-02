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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.xml.XmlDomParser;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
class CxxBoostTest extends CxxTest implements HasRuntimeDeps, ExternalTestRunnerRule {

  private static final Pattern SUITE_START = Pattern.compile("^Entering test suite \"(.*)\"$");
  private static final Pattern SUITE_END = Pattern.compile("^Leaving test suite \"(.*)\"$");

  private static final Pattern CASE_START = Pattern.compile("^Entering test case \"(.*)\"$");
  private static final Pattern CASE_END =
      Pattern.compile("^Leaving test case \"(.*)\"(?:; testing time: (\\d+)ms)?$");

  private static final Pattern ERROR = Pattern.compile("^.*\\(\\d+\\): error .*");

  private final BuildRule binary;

  public CxxBoostTest(
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
      Optional<Long> testRuleTimeoutMs) {
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
        .add("--log_format=hrf")
        .add("--log_level=test_suite")
        .add("--report_format=xml")
        .add("--report_level=detailed")
        .add("--result_code=no")
        .add("--report_sink=" + getProjectFilesystem().resolve(output))
        .build();
  }

  private void visitTestSuite(
      ImmutableList.Builder<TestResultSummary> builder,
      Map<String, String> messages,
      Map<String, List<String>> stdout,
      Map<String, Long> times,
      String prefix,
      Node testSuite) {

    NamedNodeMap attributes = testSuite.getAttributes();

    String suiteName = attributes.getNamedItem("name").getNodeValue();
    if (!prefix.isEmpty()) {
      suiteName = prefix + "." + suiteName;
    }

    NodeList testCases = testSuite.getChildNodes();
    for (int index = 0; index < testCases.getLength(); index++) {
      Node testCase = testCases.item(index);

      if (!testCase.getNodeName().equals("TestCase")) {
        visitTestSuite(builder, messages, stdout, times, suiteName, testCase);
        continue;
      }

      NamedNodeMap attrs = testCase.getAttributes();
      String caseName = attrs.getNamedItem("name").getNodeValue();
      String test = String.format("%s.%s", suiteName, caseName);
      Long time = Optional.ofNullable(times.get(test)).orElse(0L);
      String resultString = attrs.getNamedItem("result").getNodeValue();
      ResultType result = ResultType.SUCCESS;
      String output = "";
      String message = "";
      if (!"passed".equals(resultString)) {
        result = ResultType.FAILURE;
        message = messages.get(test);
        output = Joiner.on("\n").join(stdout.get(test));
      }
      builder.add(
          new TestResultSummary(
              suiteName,
              caseName,
              result,
              time,
              message,
              /* stacktrace */ "",
              /* stdOut */ output,
              /* stdErr */ ""));
    }
  }

  @Override
  protected ImmutableList<TestResultSummary> parseResults(Path exitCode, Path output, Path results)
      throws Exception {

    ImmutableList.Builder<TestResultSummary> summariesBuilder = ImmutableList.builder();

    // Process the test run output to grab the individual test stdout/stderr and
    // test run times.
    Map<String, String> messages = new HashMap<>();
    Map<String, List<String>> stdout = new HashMap<>();
    Map<String, Long> times = new HashMap<>();
    try (BufferedReader reader = Files.newBufferedReader(output, Charsets.US_ASCII)) {
      Stack<String> testSuite = new Stack<>();
      Optional<String> currentTest = Optional.empty();
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher matcher;
        if ((matcher = SUITE_START.matcher(line)).matches()) {
          String suite = matcher.group(1);
          testSuite.push(suite);
        } else if ((matcher = SUITE_END.matcher(line)).matches()) {
          String suite = matcher.group(1);
          Preconditions.checkState(testSuite.peek().equals(suite));
          testSuite.pop();
        } else if ((matcher = CASE_START.matcher(line)).matches()) {
          String test = Joiner.on(".").join(testSuite) + "." + matcher.group(1);
          currentTest = Optional.of(test);
          stdout.put(test, new ArrayList<>());
        } else if ((matcher = CASE_END.matcher(line)).matches()) {
          String test = Joiner.on(".").join(testSuite) + "." + matcher.group(1);
          Preconditions.checkState(currentTest.isPresent() && currentTest.get().equals(test));
          String time = matcher.group(2);
          times.put(test, time == null ? 0L : Long.parseLong(time));
          currentTest = Optional.empty();
        } else if (currentTest.isPresent()) {
          if (ERROR.matcher(line).matches()) {
            messages.put(currentTest.get(), line);
          } else {
            Preconditions.checkNotNull(stdout.get(currentTest.get())).add(line);
          }
        }
      }
    }

    // Parse the XML result file for the actual test result summaries.
    Document doc = XmlDomParser.parse(results);
    Node testResult = doc.getElementsByTagName("TestResult").item(0);
    Node testSuite = testResult.getFirstChild();
    visitTestSuite(summariesBuilder, messages, stdout, times, "", testSuite);

    return summariesBuilder.build();
  }

  // The C++ test rules just wrap a test binary produced by another rule, so make sure that's
  // always available to run the test.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return Stream.concat(
        super.getRuntimeDeps(ruleFinder),
        BuildableSupport.getDeps(getExecutableCommand(), ruleFinder)
            .map(BuildRule::getBuildTarget));
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {
    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("boost")
        .addAllCommand(
            getExecutableCommand().getCommandPrefix(buildContext.getSourcePathResolver()))
        .addAllCommand(Arg.stringify(getArgs(), buildContext.getSourcePathResolver()))
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
