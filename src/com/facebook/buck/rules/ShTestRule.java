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

import com.facebook.buck.shell.Command;
import com.facebook.buck.shell.ExecutionContext;
import com.facebook.buck.shell.MakeCleanDirectoryCommand;
import com.facebook.buck.util.BuckConstant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Test whose correctness is determined by running a specified shell script. If running the shell
 * script returns a non-zero error code, the test is considered a failure.
 */
public class ShTestRule extends AbstractCachingBuildRule implements TestRule {

  private final String test;
  private final ImmutableSet<String> labels;

  protected ShTestRule(
      BuildRuleParams buildRuleParams,
      String test,
      Set<String> labels) {
    super(buildRuleParams);
    this.test = Preconditions.checkNotNull(test);
    this.labels = ImmutableSet.copyOf(labels);
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("test", test);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.SH_TEST;
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput(BuildContext context) {
    return ImmutableSet.of(test);
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  @Override
  public List<Command> buildInternal(BuildContext context) {
    // Nothing to build: test is run directly.
    return ImmutableList.of();
  }

  @Override
  public List<Command> runTests(BuildContext buildContext, ExecutionContext executionContext) {
    Preconditions.checkState(isRuleBuilt(), "%s must be built before tests can be run.", this);

    // If this rule was cached, then no commands are necessary to run the tests. The results will be
    // read from the JSON file in interpretTestResults();
    if (isRuleBuiltFromCache()) {
      return ImmutableList.of();
    }

    Command mkdirClean = new MakeCleanDirectoryCommand(getPathToTestOutputDirectory());

    // Return a single command that runs an .sh file with no arguments.
    Command runTest = new RunShTestAndRecordResultCommand(test, getPathToTestOutputResult());

    return ImmutableList.of(mkdirClean, runTest);
  }

  private String getPathToTestOutputDirectory() {
    return String.format("%s/%s/__sh_test_%s_output__",
        BuckConstant.GEN_DIR,
        getBuildTarget().getBasePath(),
        getBuildTarget().getShortName());
  }

  private String getPathToTestOutputResult() {
    return getPathToTestOutputDirectory() + "/result.json";
  }

  @Override
  public Callable<TestResults> interpretTestResults() {
    return new Callable<TestResults>() {

      @Override
      public TestResults call() throws Exception {
        File resultsFile = new File(getPathToTestOutputResult());
        ObjectMapper mapper = new ObjectMapper();
        TestResultSummary testResultSummary = mapper.readValue(resultsFile,
            TestResultSummary.class);
        TestCaseSummary testCaseSummary = new TestCaseSummary(
            getFullyQualifiedName(), ImmutableList.of(testResultSummary));
        return new TestResults(ImmutableList.of(testCaseSummary));
      }

    };
  }

  public static Builder newShTestRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder implements LabelsAttributeBuilder {

    private String test;
    private ImmutableSet<String> labels = ImmutableSet.of();

    @Override
    public BuildRule build(Map<String, BuildRule> buildRuleIndex) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(buildRuleIndex);
      return new ShTestRule(buildRuleParams, test, labels);
    }

    public Builder setTest(String test) {
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
