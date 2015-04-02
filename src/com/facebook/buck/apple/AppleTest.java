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

package com.facebook.buck.apple;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class AppleTest extends AbstractBuildRule implements TestRule {

  private final BuildRule testBundle;
  private final ImmutableSet<String> contacts;
  private final ImmutableSet<Label> labels;
  private final ImmutableSet<BuildRule> sourceUnderTest;

  AppleTest(
      BuildRuleParams params,
      SourcePathResolver resolver,
      BuildRule testBundle,
      ImmutableSet<String> contacts,
      ImmutableSet<Label> labels,
      ImmutableSet<BuildRule> sourceUnderTest) {
    super(params, resolver);
    this.testBundle = testBundle;
    this.contacts = contacts;
    this.labels = labels;
    this.sourceUnderTest = sourceUnderTest;
  }

  /**
   * Returns the test bundle to run.
   */
  public BuildRule getTestBundle() {
    return testBundle;
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
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  @Nullable
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
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
      TestSelectorList testSelectorList) {
    // TODO(user): Make iOS tests runnable by Buck.
    return ImmutableList.of();
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      boolean isUsingTestSelectors,
      boolean isDryRun) {
    return new Callable<TestResults>() {
      @Override
      public TestResults call() throws Exception {
        // TODO(user): Make iOS tests runnable by Buck.
        return new TestResults(
            getBuildTarget(),
            ImmutableList.<TestCaseSummary>of(),
            contacts,
            FluentIterable.from(labels).transform(Functions.toStringFunction()).toSet());
      }
    };
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    // TODO(user): Make iOS tests runnable by Buck.
    return Paths.get("testOutputDirectory");
  }
}
