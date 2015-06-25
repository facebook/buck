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
package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.concurrent.Callable;

public class FakeTestRule extends AbstractBuildRule implements TestRule {

  private final ImmutableSet<Label> labels;
  private final Optional<Path> pathToTestOutputDirectory;
  private final boolean runTestSeparately;
  private final ImmutableList<Step> testSteps;
  private final Callable<TestResults> interpretedTestResults;

  public FakeTestRule(
      ImmutableSet<Label> labels,
      BuildTarget target,
      SourcePathResolver resolver,
      ImmutableSortedSet<BuildRule> deps) {
    this(
        new FakeBuildRuleParamsBuilder(target)
            .setDeps(deps)
            .build(),
        resolver,
        labels
    );
  }

  public FakeTestRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      ImmutableSet<Label> labels) {
    this(
        buildRuleParams,
        resolver,
        labels,
        Optional.<Path>absent(),
        false, // runTestSeparately
        ImmutableList.<Step>of(),
        new Callable<TestResults>() {
          @Override
          public TestResults call() {
            throw new UnsupportedOperationException("interpretTestResults() not implemented");
          }
        });
  }

  public FakeTestRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      ImmutableSet<Label> labels,
      Optional<Path> pathToTestOutputDirectory,
      boolean runTestSeparately,
      ImmutableList<Step> testSteps,
      Callable<TestResults> interpretedTestResults) {
    super(buildRuleParams, resolver);
    this.labels = labels;
    this.pathToTestOutputDirectory = pathToTestOutputDirectory;
    this.runTestSeparately = runTestSeparately;
    this.testSteps = testSteps;
    this.interpretedTestResults = interpretedTestResults;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public Path getPathToOutput() {
    return null;
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    return false;
  }

  @Override
  public ImmutableList<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      boolean isDryRun,
      boolean isShufflingTests,
      TestSelectorList testSelectorList,
      TestRule.TestReportingCallback testReportingCallback) {
    return testSteps;
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      boolean isUsingTestSelectors,
      boolean isDryRun) {
    return interpretedTestResults;
  }

  @Override
  public ImmutableSet<Label> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<BuildRule> getSourceUnderTest() {
    return ImmutableSet.of();
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    if (!pathToTestOutputDirectory.isPresent()) {
      throw new UnsupportedOperationException("getPathToTestOutput() not supported in fake");
    } else {
      return pathToTestOutputDirectory.get();
    }
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
