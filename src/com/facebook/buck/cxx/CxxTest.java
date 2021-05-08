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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CustomHashedBuckOutLinking;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.isolatedsteps.common.TouchStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.util.Memoizer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Stream;

/** A no-op {@link BuildRule} which houses the logic to run and form the results for C/C++ tests. */
public abstract class CxxTest extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements TestRule,
        HasRuntimeDeps,
        BinaryBuildRule,
        ExternalTestRunnerRule,
        CustomHashedBuckOutLinking {

  private final ImmutableMap<String, Arg> env;
  private final ImmutableList<Arg> args;
  private final BuildRule binary;
  private final Tool executable;

  @AddToRuleKey private final ImmutableMap<CxxResourceName, SourcePath> resources;

  private final ImmutableSet<SourcePath> additionalCoverageTargets;
  private final Function<SourcePathRuleFinder, ImmutableSortedSet<BuildRule>>
      additionalDepsSupplier;
  private final Memoizer<ImmutableSortedSet<BuildRule>> additionalDeps = new Memoizer<>();
  private final ImmutableSet<String> labels;
  private final ImmutableSet<String> contacts;
  private final boolean runTestSeparately;
  private final Optional<Long> testRuleTimeoutMs;
  private final CxxTestType cxxTestType;
  protected final boolean withDownwardApi;
  private final boolean isStandalone;

  public CxxTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRule binary,
      Tool executable,
      ImmutableMap<String, Arg> env,
      ImmutableList<Arg> args,
      ImmutableMap<CxxResourceName, SourcePath> resources,
      ImmutableSet<SourcePath> additionalCoverageTargets,
      Function<SourcePathRuleFinder, ImmutableSortedSet<BuildRule>> additionalDepsSupplier,
      ImmutableSet<String> labels,
      ImmutableSet<String> contacts,
      boolean runTestSeparately,
      Optional<Long> testRuleTimeoutMs,
      CxxTestType cxxTestType,
      boolean withDownwardApi,
      boolean isStandalone) {
    super(buildTarget, projectFilesystem, params);
    this.binary = binary;
    this.executable = executable;
    this.env = env;
    this.args = args;
    this.resources = resources;
    this.additionalCoverageTargets = additionalCoverageTargets;
    this.additionalDepsSupplier = additionalDepsSupplier;
    this.labels = labels;
    this.contacts = contacts;
    this.runTestSeparately = runTestSeparately;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.cxxTestType = cxxTestType;
    this.withDownwardApi = withDownwardApi;
    this.isStandalone = isStandalone;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(
        getBuildTarget(), Objects.requireNonNull(binary.getSourcePathToOutput()));
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return executable;
  }

  // Return a path that's consistently locatable from the binary output path.
  private RelPath getResourcesFile(SourcePathResolverAdapter resolver) {
    RelPath output = resolver.getCellUnsafeRelPath(binary.getSourcePathToOutput());
    return MorePaths.appendSuffix(output, ".resources.json");
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    CxxResourceUtils.addResourceSteps(
        buildableContext, context.getSourcePathResolver(), binary, resources, builder);
    return builder.build();
  }

  /** @return the path to which the test exit code output is written. */
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
      SourcePathResolverAdapter pathResolver, Path output);

  @Override
  public ImmutableList<Step> runTests(
      StepExecutionContext executionContext,
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
        .add(new TouchStep(getPathToTestResults()))
        .add(
            new CxxTestStep(
                getProjectFilesystem(),
                ImmutableList.<String>builder()
                    .addAll(
                        getShellCommand(
                            buildContext.getSourcePathResolver(), getPathToTestResults()))
                    .addAll(Arg.stringify(getArgs(), buildContext.getSourcePathResolver()))
                    .build(),
                getEnv(buildContext.getSourcePathResolver()),
                getPathToTestExitCode(),
                getPathToTestOutput(),
                testRuleTimeoutMs,
                withDownwardApi))
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
      StepExecutionContext executionContext,
      SourcePathResolverAdapter pathResolver,
      boolean isUsingTestSelectors) {
    return () ->
        TestResults.of(
            getBuildTarget(),
            ImmutableList.of(
                new TestCaseSummary(
                    getBuildTarget().getFullyQualifiedName(),
                    parseResults(
                        getPathToTestExitCode(), getPathToTestOutput(), getPathToTestResults()))),
            contacts,
            labels.stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()));
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
    return BuildTargetPaths.getGenPath(
            getProjectFilesystem().getBuckPaths(), getBuildTarget(), "__test_%s_output__")
        .getPath();
  }

  @Override
  public boolean runTestSeparately() {
    return runTestSeparately;
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  // The C++ test rules just wrap a test binary produced by another rule, so make sure that's
  // always available to run the test.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    ImmutableList.Builder<BuildTarget> deps = ImmutableList.builder();
    additionalDeps.get(() -> additionalDepsSupplier.apply(buildRuleResolver)).stream()
        .map(BuildRule::getBuildTarget)
        .forEach(deps::add);
    BuildableSupport.getDeps(getExecutableCommand(OutputLabel.defaultLabel()), buildRuleResolver)
        .map(BuildRule::getBuildTarget)
        .forEach(deps::add);
    resources.values().stream()
        .flatMap(r -> Streams.stream(buildRuleResolver.getRule(r)))
        .map(BuildRule::getBuildTarget)
        .forEach(deps::add);
    return deps.build().stream();
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      StepExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {

    List<Path> requiredPaths = new ArrayList<>();

    // Extract the shared library link tree from the command and add it to required paths so that
    // external runners now to ship it remotely.
    BuildableSupport.deriveInputs(executable)
        .map(
            sourcePath ->
                buildContext.getSourcePathResolver().getAbsolutePath(sourcePath).getPath())
        .forEach(requiredPaths::add);

    // Extract any required paths from args/env.
    for (Arg arg : Iterables.concat(args, env.values())) {
      BuildableSupport.deriveInputs(arg)
          .map(
              sourcePath ->
                  buildContext.getSourcePathResolver().getAbsolutePath(sourcePath).getPath())
          .forEach(requiredPaths::add);
    }

    // Add resources to required paths.
    resources.values().stream()
        .map(
            sourcePath ->
                buildContext.getSourcePathResolver().getAbsolutePath(sourcePath).getPath())
        .forEach(requiredPaths::add);

    // Add path to resources file.
    if (!resources.isEmpty()) {
      requiredPaths.add(
          getProjectFilesystem()
              .resolve(getResourcesFile(buildContext.getSourcePathResolver()))
              .getPath());
    }

    return ExternalTestRunnerTestSpec.builder()
        .setCwd(getProjectFilesystem().getRootPath().getPath())
        .setTarget(getBuildTarget())
        .setType(cxxTestType.testSpecType)
        .addAllCommand(
            getExecutableCommand(OutputLabel.defaultLabel())
                .getCommandPrefix(buildContext.getSourcePathResolver()))
        .addAllCommand(Arg.stringify(getArgs(), buildContext.getSourcePathResolver()))
        .putAllEnv(getEnv(buildContext.getSourcePathResolver()))
        .addAllLabels(getLabels())
        .addAllContacts(getContacts())
        .addAllAdditionalCoverageTargets(
            buildContext.getSourcePathResolver().getAllAbsolutePaths(getAdditionalCoverageTargets())
                .stream()
                .map(AbsPath::getPath)
                .collect(ImmutableList.toImmutableList()))
        .setRequiredPaths(requiredPaths)
        .build();
  }

  protected ImmutableMap<String, String> getEnv(SourcePathResolverAdapter pathResolver) {
    return new ImmutableMap.Builder<String, String>()
        .putAll(executable.getEnvironment(pathResolver))
        .putAll(Arg.stringify(env, pathResolver))
        .build();
  }

  @VisibleForTesting
  BuildRule getBinary() {
    return binary;
  }

  protected ImmutableList<Arg> getArgs() {
    return args;
  }

  @Override
  public boolean supportsHashedBuckOutHardLinking() {
    return isStandalone;
  }
}
