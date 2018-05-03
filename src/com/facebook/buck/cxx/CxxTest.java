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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** A no-op {@link BuildRule} which houses the logic to run and form the results for C/C++ tests. */
abstract class CxxTest extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements TestRule, HasRuntimeDeps, BinaryBuildRule {

  @AddToRuleKey private final ImmutableMap<String, Arg> env;
  @AddToRuleKey private final Supplier<ImmutableList<Arg>> args;
  @AddToRuleKey private final Tool executable;

  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final ImmutableSortedSet<? extends SourcePath> resources;

  private final ImmutableSet<SourcePath> additionalCoverageTargets;
  private final Supplier<ImmutableSortedSet<BuildRule>> additionalDeps;
  private final ImmutableSet<String> labels;
  private final ImmutableSet<String> contacts;
  private final boolean runTestSeparately;
  private final Optional<Long> testRuleTimeoutMs;

  public CxxTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Tool executable,
      ImmutableMap<String, Arg> env,
      Supplier<ImmutableList<Arg>> args,
      ImmutableSortedSet<? extends SourcePath> resources,
      ImmutableSet<SourcePath> additionalCoverageTargets,
      Supplier<ImmutableSortedSet<BuildRule>> additionalDeps,
      ImmutableSet<String> labels,
      ImmutableSet<String> contacts,
      boolean runTestSeparately,
      Optional<Long> testRuleTimeoutMs) {
    super(buildTarget, projectFilesystem, params);
    this.executable = executable;
    this.env = env;
    this.args = MoreSuppliers.memoize(args::get);
    this.resources = resources;
    this.additionalCoverageTargets = additionalCoverageTargets;
    this.additionalDeps = MoreSuppliers.memoize(additionalDeps::get);
    this.labels = labels;
    this.contacts = contacts;
    this.runTestSeparately = runTestSeparately;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
  }

  @Override
  public Tool getExecutableCommand() {
    return executable;
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  /** @return the path to which the test commands output is written. */
  @VisibleForTesting
  protected Path getPathToTestExitCode() {
    return getPathToTestOutputDirectory().resolve("exitCode");
  }

  /** @return the path to which the test commands output is written. */
  @VisibleForTesting
  protected Path getPathToTestOutput() {
    return getPathToTestOutputDirectory().resolve("output");
  }

  /** @return the path to which the framework-specific test results are written. */
  @VisibleForTesting
  protected Path getPathToTestResults() {
    return getPathToTestOutputDirectory().resolve("results");
  }

  /** @return the shell command used to run the test. */
  protected abstract ImmutableList<String> getShellCommand(
      SourcePathResolver pathResolver, Path output);

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
        .add(new TouchStep(getProjectFilesystem(), getPathToTestResults()))
        .add(
            new CxxTestStep(
                getProjectFilesystem(),
                ImmutableList.<String>builder()
                    .addAll(
                        getShellCommand(
                            buildContext.getSourcePathResolver(), getPathToTestResults()))
                    .addAll(Arg.stringify(args.get(), buildContext.getSourcePathResolver()))
                    .build(),
                getEnv(buildContext.getSourcePathResolver()),
                getPathToTestExitCode(),
                getPathToTestOutput(),
                testRuleTimeoutMs))
        .build();
  }

  protected abstract ImmutableList<TestResultSummary> parseResults(
      Path exitCode, Path output, Path results) throws Exception;

  /**
   * Parses a file that contains an exit code that was written by {@link
   * com.facebook.buck.step.AbstractTestStep}
   *
   * @param exitCodePath The path to the file that contains the exit code
   * @return The parsed exit code
   * @throws IOException The file could not be parsed. It may not exit, or has invalid data
   */
  static int parseExitCode(Path exitCodePath) throws IOException {
    try (InputStream inputStream = Files.newInputStream(exitCodePath);
        ObjectInputStream objectIn = new ObjectInputStream(inputStream)) {
      return objectIn.readInt();
    }
  }

  /**
   * Validates that the exit code in the file does not indicate an abnormal exit of the test program
   *
   * @param exitCodePath The path to the file that contains the exit code
   * @return Error message if the file could not be parsed, or the exit code indicates that the test
   *     program did not die gracefully. This does not indicate that a test failed, only that a
   *     signal was received by the test binary. Otherwise empty optional.
   */
  static Optional<String> validateExitCode(Path exitCodePath) {
    // If we failed with an exit code >128 the binary was killed by a signal (on most posix
    // systems).
    // This is often sigabrt that can happen after the test has printed "success" but before the
    // binary has shut down properly.
    try {
      int realExitCode = parseExitCode(exitCodePath);
      if (realExitCode > 128) {
        return Optional.of(
            String.format("The program was killed by signal %s", realExitCode - 128));
      }
      return Optional.empty();
    } catch (IOException e) {
      return Optional.of("Could not parse exit code from file");
    }
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      SourcePathResolver pathResolver,
      boolean isUsingTestSelectors) {
    return () -> {
      return TestResults.of(
          getBuildTarget(),
          ImmutableList.of(
              new TestCaseSummary(
                  getBuildTarget().getFullyQualifiedName(),
                  parseResults(
                      getPathToTestExitCode(), getPathToTestOutput(), getPathToTestResults()))),
          contacts,
          labels.stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()));
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

  protected ImmutableSet<SourcePath> getAdditionalCoverageTargets() {
    return additionalCoverageTargets;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__test_%s_output__");
  }

  @Override
  public boolean runTestSeparately() {
    return runTestSeparately;
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return additionalDeps.get().stream().map(BuildRule::getBuildTarget);
  }

  protected ImmutableMap<String, String> getEnv(SourcePathResolver pathResolver) {
    return new ImmutableMap.Builder<String, String>()
        .putAll(executable.getEnvironment(pathResolver))
        .putAll(Arg.stringify(env, pathResolver))
        .build();
  }

  protected Supplier<ImmutableList<Arg>> getArgs() {
    return args;
  }
}
