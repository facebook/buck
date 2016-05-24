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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Test whose correctness is determined by running a specified shell script. If running the shell
 * script returns a non-zero error code, the test is considered a failure.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class ShTest
    extends NoopBuildRule
    implements TestRule, HasRuntimeDeps, ExternalTestRunnerRule {

  @AddToRuleKey
  private final SourcePath test;
  @AddToRuleKey
  private final ImmutableList<Arg> args;
  @AddToRuleKey
  private final ImmutableMap<String, Arg> env;
  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final ImmutableSortedSet<SourcePath> resources;
  private final Optional<Long> testRuleTimeoutMs;
  private final ImmutableSet<String> contacts;
  private final ImmutableSet<Label> labels;

  protected ShTest(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath test,
      ImmutableList<Arg> args,
      ImmutableMap<String, Arg> env,
      ImmutableSortedSet<SourcePath> resources,
      Optional<Long> testRuleTimeoutMs,
      Set<Label> labels,
      ImmutableSet<String> contacts) {
    super(params, resolver);
    this.test = test;
    this.args = args;
    this.env = env;
    this.resources = resources;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.labels = ImmutableSet.copyOf(labels);
    this.contacts = contacts;
  }

  @Override
  public ImmutableSet<Label> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  @Override
  public ImmutableSet<BuildRule> getSourceUnderTest() {
    return ImmutableSet.of();
  }

  @Override
  public boolean hasTestResultFiles() {
    // If result.json was not written, then the test needs to be run.
    return getProjectFilesystem().isFile(getPathToTestOutputResult());
  }

  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      TestReportingCallback testReportingCallback) {
    if (options.isDryRun()) {
      // Stop now if we are a dry-run: sh-tests have no concept of dry-run inside the test itself.
      return ImmutableList.of();
    }

    Step mkdirClean = new MakeCleanDirectoryStep(
        getProjectFilesystem(),
        getPathToTestOutputDirectory());

    // Return a single command that runs an .sh file with no arguments.
    Step runTest =
        new RunShTestAndRecordResultStep(
            getProjectFilesystem(),
            getResolver().getAbsolutePath(test),
            Arg.stringify(args),
            Arg.stringify(env),
            testRuleTimeoutMs,
            getBuildTarget().getFullyQualifiedName(),
            getPathToTestOutputResult());

    return ImmutableList.of(mkdirClean, runTest);
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(),
        getBuildTarget(),
        "__java_test_%s_output__");
  }

  @VisibleForTesting
  Path getPathToTestOutputResult() {
    return getPathToTestOutputDirectory().resolve("result.json");
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      final ExecutionContext context,
      boolean isUsingTestSelectors,
      boolean isDryRun) {

    if (isDryRun) {
      // Again, shortcut to returning no results, because sh-tests have no concept of a dry-run.
      return new Callable<TestResults>() {
        @Override
        public TestResults call() throws Exception {
          return TestResults.of(
              getBuildTarget(),
              ImmutableList.<TestCaseSummary>of(),
              contacts,
              FluentIterable.from(labels).transform(Functions.toStringFunction()).toSet());
        }
      };
    } else {
      return new Callable<TestResults>() {

        @Override
        public TestResults call() throws Exception {
          Optional<String> resultsFileContents =
              getProjectFilesystem().readFileIfItExists(getPathToTestOutputResult());
          ObjectMapper mapper = context.getObjectMapper();
          TestResultSummary testResultSummary = mapper.readValue(resultsFileContents.get(),
              TestResultSummary.class);
          TestCaseSummary testCaseSummary = new TestCaseSummary(
              getBuildTarget().getFullyQualifiedName(),
              ImmutableList.of(testResultSummary));
          return TestResults.of(
              getBuildTarget(),
              ImmutableList.of(testCaseSummary),
              contacts,
              FluentIterable.from(labels).transform(Functions.toStringFunction()).toSet());
        }

      };
    }
  }

  @Override
  public boolean runTestSeparately() {
    return false;
  }

  // A shell test has no real build dependencies.  Instead interpret the dependencies as runtime
  // dependencies, as these are always components that the shell test needs available to run.
  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return getDeps();
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions) {
    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("custom")
        .addCommand(getResolver().getAbsolutePath(test).toString())
        .addAllCommand(Arg.stringify(args))
        .setEnv(Arg.stringify(env))
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
