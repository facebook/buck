/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Test whose correctness is determined by running a specified shell script. If running the shell
 * script returns a non-zero error code, the test is considered a failure.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class ShTest extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements TestRule, HasRuntimeDeps, ExternalTestRunnerRule, BinaryBuildRule {

  private final ImmutableList<Arg> args;
  private final ImmutableMap<String, Arg> env;
  private final Optional<String> type;
  private final ImmutableSortedSet<? extends SourcePath> resources;
  private final Optional<Long> testRuleTimeoutMs;
  private final ImmutableSet<String> contacts;
  private final boolean runTestSeparately;
  private final ImmutableSet<String> labels;

  protected ShTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ImmutableList<Arg> args,
      ImmutableMap<String, Arg> env,
      ImmutableSortedSet<? extends SourcePath> resources,
      Optional<Long> testRuleTimeoutMs,
      boolean runTestSeparately,
      Set<String> labels,
      Optional<String> type,
      ImmutableSet<String> contacts) {
    super(buildTarget, projectFilesystem, params);
    this.args = args;
    this.env = env;
    this.resources = resources;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.runTestSeparately = runTestSeparately;
    this.labels = ImmutableSet.copyOf(labels);
    this.type = type;
    this.contacts = contacts;
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
            // Return a single command that runs an .sh file with no arguments.
            new RunShTestAndRecordResultStep(
                getProjectFilesystem(),
                Arg.stringify(args, buildContext.getSourcePathResolver()),
                Arg.stringify(env, buildContext.getSourcePathResolver()),
                testRuleTimeoutMs,
                getBuildTarget().getFullyQualifiedName(),
                getPathToTestOutputResult()))
        .build();
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "__sh_test_%s_output__");
  }

  @VisibleForTesting
  Path getPathToTestOutputResult() {
    return getPathToTestOutputDirectory().resolve("result.json");
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext context, SourcePathResolver pathResolver, boolean isUsingTestSelectors) {
    return () -> {
      Optional<String> resultsFileContents =
          getProjectFilesystem().readFileIfItExists(getPathToTestOutputResult());
      TestResultSummary testResultSummary =
          ObjectMappers.readValue(resultsFileContents.get(), TestResultSummary.class);
      TestCaseSummary testCaseSummary =
          new TestCaseSummary(
              getBuildTarget().getFullyQualifiedName(), ImmutableList.of(testResultSummary));
      return TestResults.of(
          getBuildTarget(),
          ImmutableList.of(testCaseSummary),
          contacts,
          labels.stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()));
    };
  }

  @Override
  public boolean runTestSeparately() {
    return runTestSeparately;
  }

  // A shell test has no real build dependencies.  Instead interpret the dependencies as runtime
  // dependencies, as these are always components that the shell test needs available to run.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return getBuildDeps().stream().map(BuildRule::getBuildTarget);
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public Tool getExecutableCommand() {
    CommandTool.Builder builder = new CommandTool.Builder().addInputs(resources);

    for (Arg arg : args) {
      builder.addArg(arg);
    }
    for (ImmutableMap.Entry<String, Arg> envVar : env.entrySet()) {
      builder.addEnv(envVar.getKey(), envVar.getValue());
    }
    return builder.build();
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {
    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType(type.orElse("custom"))
        .addAllCommand(Arg.stringify(args, buildContext.getSourcePathResolver()))
        .setEnv(Arg.stringify(env, buildContext.getSourcePathResolver()))
        .addAllLabels(getLabels())
        .addAllContacts(getContacts())
        .build();
  }

  @VisibleForTesting
  protected ImmutableList<Arg> getArgs() {
    return args;
  }

  @VisibleForTesting
  protected ImmutableMap<String, Arg> getEnv() {
    return env;
  }
}
