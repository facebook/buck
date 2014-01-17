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

import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.LabelsAttributeBuilder;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Test whose correctness is determined by running a specified shell script. If running the shell
 * script returns a non-zero error code, the test is considered a failure.
 */
public class ShTestRule extends DoNotUseAbstractBuildable implements TestRule {

  private final Path test;
  private final ImmutableSet<String> labels;

  protected ShTestRule(
      BuildRuleParams buildRuleParams,
      Path test,
      Set<String> labels) {
    super(buildRuleParams);
    this.test = Preconditions.checkNotNull(test);
    this.labels = ImmutableSet.copyOf(labels);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.SH_TEST;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableSet.of(test);
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
  public ImmutableSet<BuildRule> getSourceUnderTest() {
    return ImmutableSet.of();
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
    // Nothing to build: test is run directly.
    return ImmutableList.of();
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
      Optional<TestSelectorList> testSelectorList) {
    Preconditions.checkState(isRuleBuilt(), "%s must be built before tests can be run.", this);

    Step mkdirClean = new MakeCleanDirectoryStep(getPathToTestOutputDirectory());

    // Return a single command that runs an .sh file with no arguments.
    Step runTest = new RunShTestAndRecordResultStep(test, getPathToTestOutputResult());

    return ImmutableList.of(mkdirClean, runTest);
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return Paths.get(
        BuckConstant.GEN_DIR,
        getBuildTarget().getBasePath(),
        String.format("__java_test_%s_output__", getBuildTarget().getShortName())
    );
  }

  @VisibleForTesting
  String getPathToTestOutputResult() {
    return getPathToTestOutputDirectory() + "/result.json";
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext context,
      boolean isUsingTestSelectors) {
    final ImmutableSet<String> contacts = getContacts();
    return new Callable<TestResults>() {

      @Override
      public TestResults call() throws Exception {
        File resultsFile = new File(getPathToTestOutputResult());
        ObjectMapper mapper = new ObjectMapper();
        TestResultSummary testResultSummary = mapper.readValue(resultsFile,
            TestResultSummary.class);
        TestCaseSummary testCaseSummary = new TestCaseSummary(
            getFullyQualifiedName(), ImmutableList.of(testResultSummary));
        return new TestResults(getBuildTarget(), ImmutableList.of(testCaseSummary), contacts);
      }

    };
  }

  public static Builder newShTestRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<ShTestRule> implements
      LabelsAttributeBuilder {

    private Path test;
    private ImmutableSet<String> labels = ImmutableSet.of();

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public ShTestRule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      return new ShTestRule(buildRuleParams, test, labels);
    }

    public Builder setTest(Path test) {
      this.test = test;
      return this;
    }

    @Override
    public Builder setLabels(ImmutableSet<String> labels) {
      this.labels = labels;
      return this;
    }
  }
}
