/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.AbstractTestStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.result.type.ResultType;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class RustTest extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule, TestRule, ExternalTestRunnerRule, HasRuntimeDeps {

  private final ImmutableSet<String> labels;
  private final ImmutableSet<String> contacts;

  private final BinaryBuildRule testExeBuild;

  private static final Pattern TEST_STDOUT_PATTERN =
      Pattern.compile("^---- (?<name>.+) stdout ----$");
  private static final Pattern FAILURES_LIST_PATTERN = Pattern.compile("^failures:$");
  private final Path testOutputFile;
  private final Path testStdoutFile;

  protected RustTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BinaryBuildRule testExeBuild,
      ImmutableSet<String> labels,
      ImmutableSet<String> contacts) {
    super(buildTarget, projectFilesystem, params);

    this.testExeBuild = testExeBuild;
    this.labels = labels;
    this.contacts = contacts;
    this.testOutputFile = getProjectFilesystem().resolve(getPathToTestResults());
    this.testStdoutFile = getProjectFilesystem().resolve(getPathToTestStdout());
  }

  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      BuildContext buildContext,
      TestReportingCallback testReportingCallback) {
    Path workingDirectory = getProjectFilesystem().resolve(getPathToTestOutputDirectory());
    return new ImmutableList.Builder<Step>()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(), getProjectFilesystem(), workingDirectory)))
        .add(
            new AbstractTestStep(
                "rust test",
                getProjectFilesystem(),
                Optional.of(workingDirectory),
                getTestCommand(
                    buildContext.getSourcePathResolver(), "--logfile", testOutputFile.toString()),
                Optional.empty(), // TODO(stash): environment
                workingDirectory.resolve("exitcode"),
                Optional.empty(),
                testStdoutFile) {})
        .build();
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      SourcePathResolver pathResolver,
      boolean isUsingTestSelectors) {
    return () -> {
      ImmutableList<TestCaseSummary> summaries = ImmutableList.of();
      summaries =
          ImmutableList.of(
              new TestCaseSummary(getBuildTarget().getFullyQualifiedName(), parseTestResults()));
      return TestResults.of(
          getBuildTarget(),
          summaries,
          getContacts(),
          getLabels().stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()));
    };
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  @Override
  public boolean runTestSeparately() {
    return false;
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {
    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("rust")
        .addAllCommand(getTestCommand(buildContext.getSourcePathResolver()))
        .addAllLabels(getLabels())
        .addAllContacts(getContacts())
        .build();
  }

  private ImmutableList<String> getTestCommand(
      SourcePathResolver pathResolver, String... additionalArgs) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(testExeBuild.getExecutableCommand().getCommandPrefix(pathResolver));
    args.add(additionalArgs);
    return args.build();
  }

  private Path getPathToTestResults() {
    return getPathToTestOutputDirectory().resolve("output");
  }

  private Path getPathToTestStdout() {
    return getPathToTestOutputDirectory().resolve("stdout");
  }

  private ImmutableList<TestResultSummary> parseTestResults() throws IOException {

    Map<String, ResultType> testToResult = new HashMap<>();
    try (BufferedReader reader = Files.newBufferedReader(testOutputFile, Charsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] resultAndTestName = line.split(" ");
        if (resultAndTestName.length != 2) {
          throw new RuntimeException(String.format("Unknown test output format %s", line));
        }
        ResultType result;
        switch (resultAndTestName[0]) {
          case "ok":
            result = ResultType.SUCCESS;
            break;
          case "failed":
            result = ResultType.FAILURE;
            break;
          case "ignored":
            result = ResultType.DISABLED;
            break;
          default:
            throw new RuntimeException(String.format("Unknown test status %s", line));
        }
        testToResult.put(resultAndTestName[1], result);
      }
    }

    Map<String, String> testToStdout = new HashMap<>();
    try (BufferedReader reader = Files.newBufferedReader(testStdoutFile, Charsets.UTF_8)) {

      StringBuilder stdout = new StringBuilder();
      String currentStdoutTestName = null;
      BiConsumer<String, String> addTestStdout =
          (key, value) -> {
            testToStdout.put(key, value);
            stdout.setLength(0);
          };
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher matcher;
        if ((matcher = TEST_STDOUT_PATTERN.matcher(line)).matches()) {
          if (currentStdoutTestName != null) {
            // Start of stdout output for new test
            // Save current stdout
            addTestStdout.accept(currentStdoutTestName, stdout.toString());
          }
          currentStdoutTestName = matcher.group("name");
        } else if (FAILURES_LIST_PATTERN.matcher(line).matches()) {
          if (currentStdoutTestName != null) {
            addTestStdout.accept(currentStdoutTestName, stdout.toString());
          }
        } else if (currentStdoutTestName != null) {
          stdout.append("\n");
          stdout.append(line);
        }
      }
    }

    ImmutableList.Builder<TestResultSummary> summariesBuilder = ImmutableList.builder();
    for (Map.Entry<String, ResultType> entry : testToResult.entrySet()) {
      summariesBuilder.add(
          new TestResultSummary(
              "rust_test",
              entry.getKey(),
              entry.getValue(),
              0, // TODO(stash) time
              "", // message
              "", // stack trace,
              testToStdout.get(entry.getKey()),
              "" // stderr
              ));
    }
    return summariesBuilder.build();
  }

  @Override
  public Tool getExecutableCommand() {
    return testExeBuild.getExecutableCommand();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(
        getBuildTarget(), testExeBuild.getSourcePathToOutput());
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return Stream.concat(
            getDeclaredDeps().stream(),
            BuildableSupport.getDepsCollection(getExecutableCommand(), ruleFinder).stream())
        .map(BuildRule::getBuildTarget);
  }
}
