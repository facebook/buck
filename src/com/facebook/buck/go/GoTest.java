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

package com.facebook.buck.go;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class GoTest extends NoopBuildRule
    implements TestRule, HasRuntimeDeps, ExternalTestRunnerRule, BinaryBuildRule {
  private static final Pattern TEST_START_PATTERN = Pattern.compile("^=== RUN\\s+(?<name>.*)$");
  private static final Pattern TEST_FINISHED_PATTERN =
      Pattern.compile(
          "^--- (?<status>PASS|FAIL|SKIP): (?<name>.+) \\((?<duration>\\d+\\.\\d+)(?: seconds|s)\\)$");
  // Extra time to wait for the process to exit on top of the test timeout
  private static final int PROCESS_TIMEOUT_EXTRA_MS = 5000;

  private final SourcePathRuleFinder ruleFinder;
  private final GoBinary testMain;

  private final ImmutableSet<String> labels;
  private final Optional<Long> testRuleTimeoutMs;
  private final ImmutableSet<String> contacts;
  @AddToRuleKey private final boolean runTestsSeparately;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> resources;

  public GoTest(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      GoBinary testMain,
      ImmutableSet<String> labels,
      ImmutableSet<String> contacts,
      Optional<Long> testRuleTimeoutMs,
      boolean runTestsSeparately,
      ImmutableSortedSet<SourcePath> resources) {
    super(buildRuleParams);
    this.ruleFinder = ruleFinder;
    this.testMain = testMain;
    this.labels = labels;
    this.contacts = contacts;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.runTestsSeparately = runTestsSeparately;
    this.resources = resources;
  }

  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      SourcePathResolver pathResolver,
      TestReportingCallback testReportingCallback) {
    Optional<Long> processTimeoutMs =
        testRuleTimeoutMs.isPresent()
            ? Optional.of(testRuleTimeoutMs.get() + PROCESS_TIMEOUT_EXTRA_MS)
            : Optional.empty();

    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(testMain.getExecutableCommand().getCommandPrefix(pathResolver));
    args.add("-test.v");
    if (testRuleTimeoutMs.isPresent()) {
      args.add("-test.timeout", testRuleTimeoutMs.get() + "ms");
    }

    return new ImmutableList.Builder<Step>()
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getPathToTestOutputDirectory()))
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), getPathToTestWorkingDirectory()))
        .add(
            new SymlinkTreeStep(
                getProjectFilesystem(),
                getPathToTestWorkingDirectory(),
                ImmutableMap.copyOf(
                    FluentIterable.from(resources)
                        .transform(
                            input ->
                                Maps.immutableEntry(
                                    getProjectFilesystem()
                                        .getPath(
                                            pathResolver.getSourcePathName(
                                                getBuildTarget(), input)),
                                    pathResolver.getAbsolutePath(input))))))
        .add(
            new GoTestStep(
                getProjectFilesystem(),
                getPathToTestWorkingDirectory(),
                args.build(),
                testMain.getExecutableCommand().getEnvironment(pathResolver),
                getPathToTestExitCode(),
                processTimeoutMs,
                getPathToTestResults()))
        .build();
  }

  private ImmutableList<TestResultSummary> parseTestResults() throws IOException {
    ImmutableList.Builder<TestResultSummary> summariesBuilder = ImmutableList.builder();
    try (BufferedReader reader =
        Files.newBufferedReader(
            getProjectFilesystem().resolve(getPathToTestResults()), Charsets.UTF_8)) {
      Optional<String> currentTest = Optional.empty();
      List<String> stdout = new ArrayList<>();
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher matcher;
        if ((matcher = TEST_START_PATTERN.matcher(line)).matches()) {
          currentTest = Optional.of(matcher.group("name"));
        } else if ((matcher = TEST_FINISHED_PATTERN.matcher(line)).matches()) {
          if (!currentTest.orElse("").equals(matcher.group("name"))) {
            throw new RuntimeException(
                String.format(
                    "Error parsing test output: test case end '%s' does not match start '%s'",
                    matcher.group("name"), currentTest.orElse("")));
          }

          ResultType result = ResultType.FAILURE;
          if ("PASS".equals(matcher.group("status"))) {
            result = ResultType.SUCCESS;
          } else if ("SKIP".equals(matcher.group("status"))) {
            result = ResultType.ASSUMPTION_VIOLATION;
          }

          double timeTaken = 0.0;
          try {
            timeTaken = Float.parseFloat(matcher.group("duration"));
          } catch (NumberFormatException ex) {
            Throwables.throwIfUnchecked(ex);
          }

          summariesBuilder.add(
              new TestResultSummary(
                  "go_test",
                  matcher.group("name"),
                  result,
                  (long) (timeTaken * 1000),
                  "",
                  "",
                  Joiner.on(System.lineSeparator()).join(stdout),
                  ""));

          currentTest = Optional.empty();
          stdout.clear();
        } else {
          stdout.add(line);
        }
      }

      if (currentTest.isPresent()) {
        // This can happen in case of e.g. a panic.
        summariesBuilder.add(
            new TestResultSummary(
                "go_test",
                currentTest.get(),
                ResultType.FAILURE,
                0,
                "incomplete",
                "",
                Joiner.on(System.lineSeparator()).join(stdout),
                ""));
      }
    }
    return summariesBuilder.build();
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext, boolean isUsingTestSelectors) {
    return () -> {
      return TestResults.of(
          getBuildTarget(),
          ImmutableList.of(
              new TestCaseSummary(getBuildTarget().getFullyQualifiedName(), parseTestResults())),
          contacts,
          labels.stream().map(Object::toString).collect(MoreCollectors.toImmutableSet()));
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
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__test_%s_output__");
  }

  protected Path getPathToTestResults() {
    return getPathToTestOutputDirectory().resolve("results");
  }

  protected Path getPathToTestWorkingDirectory() {
    return getPathToTestOutputDirectory().resolve("working_dir");
  }

  protected Path getPathToTestExitCode() {
    return getPathToTestOutputDirectory().resolve("exitCode");
  }

  @Override
  public boolean runTestSeparately() {
    return runTestsSeparately;
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    return Stream.concat(
        Stream.of((testMain.getBuildTarget())),
        resources
            .stream()
            .map(ruleFinder::filterBuildRuleInputs)
            .flatMap(ImmutableSet::stream)
            .map(BuildRule::getBuildTarget));
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      SourcePathResolver pathResolver) {
    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("go")
        .putAllEnv(testMain.getExecutableCommand().getEnvironment(pathResolver))
        .addAllCommand(testMain.getExecutableCommand().getCommandPrefix(pathResolver))
        .addAllLabels(getLabels())
        .addAllContacts(getContacts())
        .build();
  }

  @Override
  public Tool getExecutableCommand() {
    return testMain.getExecutableCommand();
  }
}
