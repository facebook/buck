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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * A no-op {@link BuildRule} which houses the logic to run and form the results for C/C++ tests.
 */
public abstract class CxxTest extends AbstractBuildRule implements TestRule, HasRuntimeDeps {

  @AddToRuleKey
  private final Supplier<ImmutableMap<String, String>> env;
  @AddToRuleKey
  private final Supplier<ImmutableList<String>> args;
  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final ImmutableSortedSet<SourcePath> resources;
  private final Supplier<ImmutableSortedSet<BuildRule>> additionalDeps;
  private final ImmutableSet<Label> labels;
  private final ImmutableSet<String> contacts;
  private final ImmutableSet<BuildRule> sourceUnderTest;
  private final boolean runTestSeparately;
  private final Optional<Long> testRuleTimeoutMs;

  public CxxTest(
      BuildRuleParams params,
      SourcePathResolver resolver,
      final ImmutableMap<String, String> toolEnv,
      final Supplier<ImmutableMap<String, String>> env,
      Supplier<ImmutableList<String>> args,
      ImmutableSortedSet<SourcePath> resources,
      Supplier<ImmutableSortedSet<BuildRule>> additionalDeps,
      ImmutableSet<Label> labels,
      ImmutableSet<String> contacts,
      ImmutableSet<BuildRule> sourceUnderTest,
      boolean runTestSeparately,
      Optional<Long> testRuleTimeoutMs) {
    super(params, resolver);
    this.env = Suppliers.memoize(
        new Supplier<ImmutableMap<String, String>>() {
          @Override
          public ImmutableMap<String, String> get() {
            ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
            builder.putAll(toolEnv);
            builder.putAll(env.get());
            return builder.build();
          }
        });
    this.args = Suppliers.memoize(args);
    this.resources = resources;
    this.additionalDeps = Suppliers.memoize(additionalDeps);
    this.labels = labels;
    this.contacts = contacts;
    this.sourceUnderTest = sourceUnderTest;
    this.runTestSeparately = runTestSeparately;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  /**
   * @return the path to which the test commands output is written.
   */
  @VisibleForTesting
  protected Path getPathToTestExitCode() {
    return getPathToTestOutputDirectory().resolve("exitCode");
  }

  /**
   * @return the path to which the test commands output is written.
   */
  @VisibleForTesting
  protected Path getPathToTestOutput() {
    return getPathToTestOutputDirectory().resolve("output");
  }

  /**
   * @return the path to which the framework-specific test results are written.
   */
  @VisibleForTesting
  protected Path getPathToTestResults() {
    return getPathToTestOutputDirectory().resolve("results");
  }

  /**
   * @return the shell command used to run the test.
   */
  protected abstract ImmutableList<String> getShellCommand(Path output);

  @Override
  public boolean hasTestResultFiles() {
    return getProjectFilesystem().isFile(getPathToTestResults());
  }

  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      TestReportingCallback testReportingCallback) {
    return ImmutableList.of(
        new MakeCleanDirectoryStep(getProjectFilesystem(), getPathToTestOutputDirectory()),
        new TouchStep(getProjectFilesystem(), getPathToTestResults()),
        new CxxTestStep(
            getProjectFilesystem(),
            ImmutableList.<String>builder()
                .addAll(getShellCommand(getPathToTestResults()))
                .addAll(args.get())
                .build(),
            env.get(),
            getPathToTestExitCode(),
            getPathToTestOutput(),
            testRuleTimeoutMs));
  }

  protected abstract ImmutableList<TestResultSummary> parseResults(
      Path exitCode,
      Path output,
      Path results)
      throws Exception;

  @Override
  public Callable<TestResults> interpretTestResults(
      final ExecutionContext executionContext,
      boolean isUsingTestSelectors,
      final boolean isDryRun) {
    return new Callable<TestResults>() {
      @Override
      public TestResults call() throws Exception {
        ImmutableList.Builder<TestCaseSummary> summaries = ImmutableList.builder();
        if (!isDryRun) {
          ImmutableList<TestResultSummary> resultSummaries =
              parseResults(
                  getPathToTestExitCode(),
                  getPathToTestOutput(),
                  getPathToTestResults());
          TestCaseSummary summary = new TestCaseSummary(
              getBuildTarget().getFullyQualifiedName(),
              resultSummaries);
          summaries.add(summary);
        }
        return TestResults.of(
            getBuildTarget(),
            summaries.build(),
            contacts,
            FluentIterable.from(labels).transform(Functions.toStringFunction()).toSet());
      }
    };
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
    return sourceUnderTest;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(),
        getBuildTarget(),
        "__test_%s_output__");
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
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return additionalDeps.get();
  }

  protected Supplier<ImmutableMap<String, String>> getEnv() {
    return env;
  }

  protected Supplier<ImmutableList<String>> getArgs() {
    return args;
  }

}
