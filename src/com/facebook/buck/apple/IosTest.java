/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.cxx.CompilerStep;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class IosTest extends AbstractAppleNativeTargetBuildRule implements TestRule {
  private final IosTestType testType;
  private final ImmutableSet<String> contacts;
  private final ImmutableSet<Label> labels;
  private final ImmutableSet<BuildRule> sourceUnderTest;

  public IosTest(
      BuildRuleParams params,
      IosTestDescription.Arg arg,
      TargetSources targetSources) {
    super(params, arg, targetSources);
    Optional<String> argTestType = Preconditions.checkNotNull(arg.testType);
    testType = IosTestType.fromString(argTestType.or("octest"));
    contacts = Preconditions.checkNotNull(arg.contacts.get());
    labels = Preconditions.checkNotNull(arg.labels.get());
    sourceUnderTest = Preconditions.checkNotNull(arg.sourceUnderTest.get());
  }

  public IosTestType getTestType() {
    return testType;
  }

  @Override
  protected List<Step> getFinalBuildSteps(ImmutableSortedSet<Path> files, Path outputFile) {
    if (files.isEmpty()) {
      return ImmutableList.of();
    } else {
      // TODO(user): This needs to create a dylib, not a static library.
      return ImmutableList.<Step>of(
          new CompilerStep(
              /* compiler */ getCompiler(),
              /* shouldLink */ true,
              /* srcs */ files,
              /* outputFile */ outputFile,
              /* shouldAddProjectRootToIncludePaths */ false,
              /* includePaths */ ImmutableSortedSet.<Path>of(),
              /* commandLineArgs */ ImmutableList.<String>of()));
    }
  }

  @Override
  protected String getOutputFileNameFormat() {
    return "%s";
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
        return new TestResults(getBuildTarget(), ImmutableList.<TestCaseSummary>of(), contacts);
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
    // TODO(user): Make iOS tests runnable by Buck.
    return Paths.get("testOutputDirectory");
  }
}
