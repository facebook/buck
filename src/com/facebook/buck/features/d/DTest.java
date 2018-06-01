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

package com.facebook.buck.features.d;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.result.type.ResultType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class DTest extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements ExternalTestRunnerRule, HasRuntimeDeps, TestRule {
  private ImmutableSortedSet<String> contacts;
  private ImmutableSortedSet<String> labels;
  private final BuildRule testBinaryBuildRule;
  private final Optional<Long> testRuleTimeoutMs;

  public DTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRule testBinaryBuildRule,
      ImmutableSortedSet<String> contacts,
      ImmutableSortedSet<String> labels,
      Optional<Long> testRuleTimeoutMs) {
    super(buildTarget, projectFilesystem, params);
    this.contacts = contacts;
    this.labels = labels;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.testBinaryBuildRule = testBinaryBuildRule;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  private ImmutableList<String> getExecutableCommand(SourcePathResolver pathResolver) {
    return ImmutableList.of(pathResolver.getAbsolutePath(getSourcePathToOutput()).toString());
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  /** @return the path to which the test commands output is written. */
  protected Path getPathToTestExitCode() {
    return getPathToTestOutputDirectory().resolve("exitCode");
  }

  /** @return the path to which the test commands output is written. */
  protected Path getPathToTestOutput() {
    return getPathToTestOutputDirectory().resolve("output");
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__test_%s_output__");
  }

  private ImmutableList<String> getShellCommand(SourcePathResolver pathResolver) {
    return getExecutableCommand(pathResolver);
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      SourcePathResolver pathResolver,
      boolean isUsingTestSelectors) {
    return () -> {
      ResultType resultType = ResultType.FAILURE;

      // Successful exit indicates success.
      try (ObjectInputStream objectIn =
          new ObjectInputStream(
              new FileInputStream(
                  getProjectFilesystem().resolve(getPathToTestExitCode()).toFile()))) {
        int exitCode = objectIn.readInt();
        if (exitCode == 0) {
          resultType = ResultType.SUCCESS;
        }
      } catch (IOException e) {
        // Any IO error means something went awry, so it's a failure.
        resultType = ResultType.FAILURE;
      }

      String testOutput =
          getProjectFilesystem().readFileIfItExists(getPathToTestOutput()).orElse("");
      String message = "";
      String stackTrace = "";
      String testName = "";
      if (resultType == ResultType.FAILURE && !testOutput.isEmpty()) {
        // We don't get any information on successful runs, but failures usually come with
        // some information. This code parses it.
        int firstNewline = testOutput.indexOf('\n');
        String firstLine = firstNewline == -1 ? testOutput : testOutput.substring(0, firstNewline);
        // First line has format <Exception name>@<location>: <message>
        // Use <location> as test name, and <message> as message.
        Pattern firstLinePattern = Pattern.compile("^[^@]*@([^:]*): (.*)");
        Matcher m = firstLinePattern.matcher(firstLine);
        if (m.matches()) {
          testName = m.group(1);
          message = m.group(2);
        }
        // The whole output is actually a stack trace.
        stackTrace = testOutput;
      }

      TestResultSummary summary =
          new TestResultSummary(
              getBuildTarget().getShortName(),
              testName,
              resultType,
              /* time */ 0,
              message,
              stackTrace,
              testOutput,
              /* stderr */ "");

      return TestResults.of(
          getBuildTarget(),
          ImmutableList.of(new TestCaseSummary("main", ImmutableList.of(summary))),
          contacts,
          labels.stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()));
    };
  }

  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      BuildContext buildContext,
      TestReportingCallback testReportingCallback) {
    return new ImmutableList.Builder<Step>()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    getPathToTestOutputDirectory())))
        .add(
            new DTestStep(
                getProjectFilesystem(),
                getShellCommand(buildContext.getSourcePathResolver()),
                getPathToTestExitCode(),
                testRuleTimeoutMs,
                getPathToTestOutput()))
        .build();
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
        .setType("dunit")
        .setCommand(getShellCommand(buildContext.getSourcePathResolver()))
        .setLabels(getLabels())
        .setContacts(getContacts())
        .build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(
        getBuildTarget(), Preconditions.checkNotNull(testBinaryBuildRule.getSourcePathToOutput()));
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    // Return the actual executable as a runtime dependency.
    // Without this, the file is not written when we get a cache hit.
    return Stream.of(testBinaryBuildRule.getBuildTarget());
  }
}
