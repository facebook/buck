/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

public class FakeTestRule extends AbstractBuildRuleWithDeclaredAndExtraDeps implements TestRule {

  private final ImmutableSet<String> labels;
  private final Optional<Path> pathToTestOutputDirectory;
  private final boolean runTestSeparately;
  private final ImmutableList<Step> testSteps;
  private final Callable<TestResults> interpretedTestResults;

  public FakeTestRule(
      ImmutableSet<String> labels, BuildTarget target, ImmutableSortedSet<BuildRule> deps) {
    this(
        target,
        new FakeProjectFilesystem(),
        TestBuildRuleParams.create().withDeclaredDeps(deps),
        labels);
  }

  public FakeTestRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      ImmutableSet<String> labels) {
    this(
        buildTarget,
        projectFilesystem,
        buildRuleParams,
        labels,
        Optional.empty(),
        false, // runTestSeparately
        ImmutableList.of(),
        () -> {
          throw new UnsupportedOperationException("interpretTestResults() not implemented");
        });
  }

  public FakeTestRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      ImmutableSet<String> labels,
      Optional<Path> pathToTestOutputDirectory,
      boolean runTestSeparately,
      ImmutableList<Step> testSteps,
      Callable<TestResults> interpretedTestResults) {
    super(buildTarget, projectFilesystem, buildRuleParams);
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
  @Nullable
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      BuildContext buildContext,
      TestReportingCallback testReportingCallback) {
    return testSteps;
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      SourcePathResolver pathResolver,
      boolean isUsingTestSelectors) {
    return interpretedTestResults;
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
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
