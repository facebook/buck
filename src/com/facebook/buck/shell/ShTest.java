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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.ProjectFilesystem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

/**
 * Test whose correctness is determined by running a specified shell script. If running the shell
 * script returns a non-zero error code, the test is considered a failure.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class ShTest extends AbstractBuildable implements TestRule {

  private final SourcePath test;
  private final ImmutableSet<Label> labels;

  protected ShTest(
      BuildTarget buildTarget,
      SourcePath test,
      Set<Label> labels) {
    super(buildTarget);
    this.test = Preconditions.checkNotNull(test);
    this.labels = ImmutableSet.copyOf(labels);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(ImmutableList.of(test));
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
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    // Nothing to build: test is run directly.
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    // If result.json was not written, then the test needs to be run.
    ProjectFilesystem filesystem = executionContext.getProjectFilesystem();
    return filesystem.isFile(getPathToTestOutputResult());
  }

  @Override
  public List<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      boolean isDryRun,
      TestSelectorList testSelectorList) {
    if (isDryRun) {
      // Stop now if we are a dry-run: sh-tests have no concept of dry-run inside the test itself.
      return ImmutableList.of();
    }

    Step mkdirClean = new MakeCleanDirectoryStep(getPathToTestOutputDirectory());

    // Return a single command that runs an .sh file with no arguments.
    Step runTest = new RunShTestAndRecordResultStep(
        test.resolve(),
        getPathToTestOutputResult());

    return ImmutableList.of(mkdirClean, runTest);
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(
        getBuildTarget(),
        "__java_test_%s_output__");
  }

  @VisibleForTesting
  Path getPathToTestOutputResult() {
    return getPathToTestOutputDirectory().resolve("result.json");
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext context,
      boolean isUsingTestSelectors,
      boolean isDryRun) {
    final ImmutableSet<String> contacts = getContacts();
    final ProjectFilesystem filesystem = context.getProjectFilesystem();

    if (isDryRun) {
      // Again, shortcut to returning no results, because sh-tests have no concept of a dry-run.
      return new Callable<TestResults>() {
        @Override
        public TestResults call() throws Exception {
          return new TestResults(getBuildTarget(), ImmutableList.<TestCaseSummary>of(), contacts);
        }
      };
    } else {
      return new Callable<TestResults>() {

        @Override
        public TestResults call() throws Exception {
          Optional<String> resultsFileContents =
              filesystem.readFileIfItExists(getPathToTestOutputResult());
          ObjectMapper mapper = new ObjectMapper();
          TestResultSummary testResultSummary = mapper.readValue(resultsFileContents.get(),
              TestResultSummary.class);
          TestCaseSummary testCaseSummary = new TestCaseSummary(
              target.getFullyQualifiedName(),
              ImmutableList.of(testResultSummary));
          return new TestResults(getBuildTarget(), ImmutableList.of(testCaseSummary), contacts);
        }

      };
    }
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }
}
