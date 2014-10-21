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
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class CxxTestTest {

  private abstract static class FakeCxxTest extends CxxTest {

    private static BuildRuleParams createBuildParams() {
      BuildTarget target = BuildTargetFactory.newInstance("//:target");
      return BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    }

    public FakeCxxTest() {
      super(
          createBuildParams(),
          new SourcePathResolver(new BuildRuleResolver()),
          ImmutableSet.<Label>of(),
          ImmutableSet.<String>of(),
          ImmutableSet.<BuildRule>of());
    }

    @Override
    protected ImmutableList<String> getShellCommand(
        ExecutionContext context, Path output) {
      return ImmutableList.of();
    }

    @Override
    protected ImmutableList<TestResultSummary> parseResults(
        ExecutionContext context,
        Path exitCode,
        Path output,
        Path results)
        throws Exception {
      return ImmutableList.of();
    }

  }

  @Test
  public void runTests() {
    final ImmutableList<String> command = ImmutableList.of("hello", "world");

    FakeCxxTest cxxTest =
        new FakeCxxTest() {

          @Override
          protected ImmutableList<String> getShellCommand(
              ExecutionContext context, Path output) {
            return command;
          }

        };

    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    ImmutableList<Step> actualSteps = cxxTest.runTests(
        buildContext,
        executionContext,
        /* isDryRun */ false,
        /* isShufflingTests */ false,
        TestSelectorList.empty());

    CxxTestStep cxxTestStep = new CxxTestStep(
        command,
        cxxTest.getPathToTestExitCode(),
        cxxTest.getPathToTestOutput());

    assertEquals(cxxTestStep, Iterables.getLast(actualSteps));
  }

  @Test
  public void interpretResults() throws Exception {
    final Path expectedExitCode = Paths.get("output");
    final Path expectedOutput = Paths.get("output");
    final Path expectedResults = Paths.get("results");

    FakeCxxTest cxxTest =
        new FakeCxxTest() {

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
              ExecutionContext context,
              Path exitCode,
              Path output,
              Path results)
              throws Exception {
            assertEquals(expectedExitCode, exitCode);
            assertEquals(expectedOutput, output);
            assertEquals(expectedResults, results);
            return ImmutableList.of();
          }

        };

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    Callable<TestResults> result = cxxTest.interpretTestResults(
        executionContext,
        /* isUsingTestSelectors */ false,
        /* isDryRun */ false);
    result.call();
  }

}
