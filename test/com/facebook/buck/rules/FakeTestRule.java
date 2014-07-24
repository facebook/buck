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
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.concurrent.Callable;

public class FakeTestRule extends AbstractBuildRule implements TestRule {

  private final ImmutableSet<Label> labels;

  public FakeTestRule(BuildRuleType type,
                       ImmutableSet<Label> labels,
                       BuildTarget target,
                       ImmutableSortedSet<BuildRule> deps,
                       ImmutableSet<BuildTargetPattern> visibilityPatterns) {
    this(
        labels,
        new FakeBuildRuleParamsBuilder(target)
            .setDeps(deps)
            .setVisibility(visibilityPatterns)
            .setType(type)
            .build());
  }

  public FakeTestRule(ImmutableSet<Label> labels, BuildRuleParams buildRuleParams) {
    super(buildRuleParams);
    this.labels = labels;
  }

  @Override
  public Iterable<Path> getInputs() {
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  protected Iterable<Path> getInputsToCompareToOutput() {
    return null;
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
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
      TestSelectorList testSelectorList) {
    throw new UnsupportedOperationException("runTests() not supported in fake");
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      boolean isUsingTestSelectors,
      boolean isDryRun) {
    throw new UnsupportedOperationException("interpretTestResults() not supported in fake");
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
    throw new UnsupportedOperationException("getPathToTestOutput() not supported in fake");
  }
}
