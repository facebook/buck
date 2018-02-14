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

package com.facebook.buck.python;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.ForwardingBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class PythonTest extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements TestRule, HasRuntimeDeps, ExternalTestRunnerRule, BinaryBuildRule {

  private final Supplier<? extends SortedSet<BuildRule>> originalDeclaredDeps;
  private final Supplier<ImmutableMap<String, Arg>> env;
  private final PythonBinary binary;
  private final ImmutableSet<String> labels;
  private final Optional<Long> testRuleTimeoutMs;
  private final ImmutableSet<String> contacts;
  private final ImmutableList<Pair<Float, ImmutableSet<Path>>> neededCoverage;

  private PythonTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Supplier<? extends SortedSet<BuildRule>> originalDeclaredDeps,
      Supplier<ImmutableMap<String, Arg>> env,
      PythonBinary binary,
      ImmutableSet<String> labels,
      ImmutableList<Pair<Float, ImmutableSet<Path>>> neededCoverage,
      Optional<Long> testRuleTimeoutMs,
      ImmutableSet<String> contacts) {
    super(buildTarget, projectFilesystem, params);
    this.originalDeclaredDeps = originalDeclaredDeps;
    this.env = env;
    this.binary = binary;
    this.labels = labels;
    this.neededCoverage = neededCoverage;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.contacts = contacts;
  }

  public static PythonTest from(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Supplier<ImmutableMap<String, Arg>> env,
      PythonBinary binary,
      ImmutableSet<String> labels,
      ImmutableList<Pair<Float, ImmutableSet<Path>>> neededCoverage,
      Optional<Long> testRuleTimeoutMs,
      ImmutableSet<String> contacts) {
    return new PythonTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(binary)).withoutExtraDeps(),
        params.getDeclaredDeps(),
        env,
        binary,
        labels,
        neededCoverage,
        testRuleTimeoutMs,
        contacts);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(getBuildTarget(), binary.getSourcePathToOutput());
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
            new PythonRunTestsStep(
                getBuildTarget(),
                getProjectFilesystem().getRootPath(),
                getBuildTarget().getFullyQualifiedName(),
                binary
                    .getExecutableCommand()
                    .getCommandPrefix(buildContext.getSourcePathResolver()),
                getMergedEnv(buildContext.getSourcePathResolver()),
                options.getTestSelectorList(),
                testRuleTimeoutMs,
                getProjectFilesystem().resolve(getPathToTestOutputResult())))
        .build();
  }

  private ImmutableMap<String, String> getMergedEnv(SourcePathResolver pathResolver) {
    return new ImmutableMap.Builder<String, String>()
        .putAll(binary.getExecutableCommand().getEnvironment(pathResolver))
        .putAll(Arg.stringify(env.get(), pathResolver))
        .build();
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__test_%s_output__");
  }

  private Path getPathToTestOutputResult() {
    return getPathToTestOutputDirectory().resolve("results.json");
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      final ExecutionContext executionContext,
      SourcePathResolver pathResolver,
      boolean isUsingTestSelectors) {
    return () -> {
      Optional<String> resultsFileContents =
          getProjectFilesystem().readFileIfItExists(getPathToTestOutputResult());
      TestResultSummary[] testResultSummaries =
          ObjectMappers.readValue(resultsFileContents.get(), TestResultSummary[].class);
      return TestResults.of(
          getBuildTarget(),
          ImmutableList.of(
              new TestCaseSummary(
                  getBuildTarget().getFullyQualifiedName(),
                  ImmutableList.copyOf(testResultSummaries))),
          contacts,
          labels.stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()));
    };
  }

  @Override
  public boolean runTestSeparately() {
    return false;
  }

  // A python test rule is actually just a {@link NoopBuildRuleWithDeclaredAndExtraDeps} which
  // contains a references to
  // a {@link PythonBinary} rule, which is the actual test binary.  Therefore, we *need* this
  // rule around to run this test, so model this via the {@link HasRuntimeDeps} interface.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return RichStream.<BuildTarget>empty()
        .concat(originalDeclaredDeps.get().stream().map(BuildRule::getBuildTarget))
        .concat(binary.getRuntimeDeps(ruleFinder))
        .concat(
            BuildableSupport.getDepsCollection(binary.getExecutableCommand(), ruleFinder)
                .stream()
                .map(BuildRule::getBuildTarget));
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @VisibleForTesting
  protected PythonBinary getBinary() {
    return binary;
  }

  @Override
  public Tool getExecutableCommand() {
    return binary.getExecutableCommand();
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {
    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("pyunit")
        .setNeededCoverage(neededCoverage)
        .addAllCommand(
            binary.getExecutableCommand().getCommandPrefix(buildContext.getSourcePathResolver()))
        .putAllEnv(getMergedEnv(buildContext.getSourcePathResolver()))
        .addAllLabels(getLabels())
        .addAllContacts(getContacts())
        .build();
  }
}
