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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeTestRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.Test;

public class CxxTestTest {

  private static final Optional<Long> TEST_TIMEOUT_MS = Optional.of(24L);

  private abstract static class FakeCxxTest extends CxxTest {

    private static final BuildTarget buildTarget = BuildTargetFactory.newInstance("//:target");

    private static BuildRuleParams createBuildParams() {
      return TestBuildRuleParams.create();
    }

    public FakeCxxTest() {
      super(
          buildTarget,
          new FakeProjectFilesystem(),
          createBuildParams(),
          new CommandTool.Builder().build(),
          ImmutableMap.of(),
          Suppliers.ofInstance(ImmutableList.of()),
          ImmutableSortedSet.of(),
          ImmutableSet.of(),
          Suppliers.ofInstance(ImmutableSortedSet.of()),
          ImmutableSet.of(),
          ImmutableSet.of(),
          /* runTestSeparately */ false,
          TEST_TIMEOUT_MS);
    }

    @Override
    protected ImmutableList<String> getShellCommand(SourcePathResolver resolver, Path output) {
      return ImmutableList.of();
    }

    @Override
    protected ImmutableList<TestResultSummary> parseResults(
        Path exitCode, Path output, Path results) throws Exception {
      return ImmutableList.of();
    }
  }

  @Test
  public void runTests() {
    ImmutableList<String> command = ImmutableList.of("hello", "world");

    FakeCxxTest cxxTest =
        new FakeCxxTest() {

          @Override
          public SourcePath getSourcePathToOutput() {
            return ExplicitBuildTargetSourcePath.of(getBuildTarget(), Paths.get("output"));
          }

          @Override
          protected ImmutableList<String> getShellCommand(
              SourcePathResolver resolver, Path output) {
            return command;
          }

          @Override
          public Tool getExecutableCommand() {
            CommandTool.Builder builder = new CommandTool.Builder();
            command.forEach(builder::addArg);
            return builder.build();
          }
        };

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    TestRunningOptions options =
        TestRunningOptions.builder().setTestSelectorList(TestSelectorList.empty()).build();
    ImmutableList<Step> actualSteps =
        cxxTest.runTests(
            executionContext,
            options,
            FakeBuildContext.NOOP_CONTEXT,
            FakeTestRule.NOOP_REPORTING_CALLBACK);

    CxxTestStep cxxTestStep =
        new CxxTestStep(
            new FakeProjectFilesystem(),
            command,
            ImmutableMap.of(),
            cxxTest.getPathToTestExitCode(),
            cxxTest.getPathToTestOutput(),
            TEST_TIMEOUT_MS);

    assertEquals(cxxTestStep, Iterables.getLast(actualSteps));
  }

  @Test
  public void interpretResults() throws Exception {
    Path expectedExitCode = Paths.get("output");
    Path expectedOutput = Paths.get("output");
    Path expectedResults = Paths.get("results");

    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();
    FakeCxxTest cxxTest =
        new FakeCxxTest() {

          @Override
          public SourcePath getSourcePathToOutput() {
            return ExplicitBuildTargetSourcePath.of(getBuildTarget(), Paths.get("output"));
          }

          @Override
          protected Path getPathToTestExitCode() {
            return expectedExitCode;
          }

          @Override
          protected Path getPathToTestOutput() {
            return expectedOutput;
          }

          @Override
          protected Path getPathToTestResults() {
            return expectedResults;
          }

          @Override
          protected ImmutableList<TestResultSummary> parseResults(
              Path exitCode, Path output, Path results) {
            assertEquals(expectedExitCode, exitCode);
            assertEquals(expectedOutput, output);
            assertEquals(expectedResults, results);
            return ImmutableList.of();
          }
        };
    ruleResolver.addToIndex(cxxTest);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    Callable<TestResults> result =
        cxxTest.interpretTestResults(
            executionContext,
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver)),
            /* isUsingTestSelectors */ false);
    result.call();
  }
}
