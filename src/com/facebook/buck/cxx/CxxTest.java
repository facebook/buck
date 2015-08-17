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
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * A no-op {@link BuildRule} which houses the logic to run and form the results for C/C++ tests.
 */
public abstract class CxxTest extends NoopBuildRule implements TestRule {

  private final ImmutableMap<String, String> env;
  private final ImmutableSet<Label> labels;
  private final ImmutableSet<String> contacts;
  private final ImmutableSet<BuildRule> sourceUnderTest;
  private final boolean runTestSeparately;

  public CxxTest(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableMap<String, String> env,
      ImmutableSet<Label> labels,
      ImmutableSet<String> contacts,
      ImmutableSet<BuildRule> sourceUnderTest,
      boolean runTestSeparately) {
    super(params, resolver);
    this.env = env;
    this.labels = labels;
    this.contacts = contacts;
    this.sourceUnderTest = sourceUnderTest;
    this.runTestSeparately = runTestSeparately;
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
  protected abstract ImmutableList<String> getShellCommand(ExecutionContext context, Path output);

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    return getProjectFilesystem().isFile(getPathToTestResults());
  }

  @Override
  public ImmutableList<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      boolean isDryRun,
      boolean isShufflingTests,
      TestSelectorList testSelectorList,
      TestRule.TestReportingCallback testReportingCallback) {
    return ImmutableList.of(
        new MakeCleanDirectoryStep(getPathToTestOutputDirectory()),
        new TouchStep(getPathToTestResults()),
        new CxxTestStep(
            getShellCommand(executionContext, getPathToTestResults()),
            env,
            getPathToTestExitCode(),
            getPathToTestOutput()));
  }

  protected abstract ImmutableList<TestResultSummary> parseResults(
      ExecutionContext context,
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
                  executionContext,
                  getPathToTestExitCode(),
                  getPathToTestOutput(),
                  getPathToTestResults());
          TestCaseSummary summary = new TestCaseSummary(
              getBuildTarget().getFullyQualifiedName(),
              resultSummaries);
          summaries.add(summary);
        }
        return new TestResults(
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
}
