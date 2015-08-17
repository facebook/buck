/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.d;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class DTest extends DLinkable implements TestRule {
  private ImmutableSortedSet<String> contacts;
  private ImmutableSortedSet<Label> labels;
  private ImmutableSet<BuildRule> sourceUnderTest;

  public DTest(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ImmutableList<SourcePath> inputs,
      ImmutableSortedSet<String> contacts,
      ImmutableSortedSet<Label> labels,
      ImmutableSet<BuildRule> sourceUnderTest,
      Tool compiler) {
    super(
        params,
        resolver,
        inputs,
        ImmutableList.of("-unittest"),
        BuildTargets.getGenPath(
            params.getBuildTarget(), "%s/" + params.getBuildTarget().getShortName()),
        compiler);
    this.contacts = contacts;
    this.labels = labels;
    this.sourceUnderTest = sourceUnderTest;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  public ImmutableList<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    return ImmutableList.of(projectFilesystem.resolve(getPathToOutput()).toString());
  }

  @Override
  public ImmutableSet<Label> getLabels() {
    return labels;
  }

  /**
   * @return the path to which the test commands output is written.
   */
  protected Path getPathToTestExitCode() {
    return getPathToTestOutputDirectory().resolve("exitCode");
  }

  /**
   * @return the path to which the test commands output is written.
   */
  protected Path getPathToTestOutput() {
    return getPathToTestOutputDirectory().resolve("output");
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(
        getBuildTarget(),
        "__test_%s_output__");
  }

  @Override
  public BuildableProperties getProperties() {
    return new BuildableProperties(BuildableProperties.Kind.TEST);
  }

  private ImmutableList<String> getShellCommand() {
    return getExecutableCommand(getProjectFilesystem());
  }

  @Override
  public ImmutableSet<BuildRule> getSourceUnderTest() {
    return sourceUnderTest;
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    return getProjectFilesystem().isFile(getPathToTestOutput());
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      final ExecutionContext executionContext,
      boolean isUsingTestSelectors,
      final boolean isDryRun) {
    return new Callable<TestResults>() {
      @Override
      public TestResults call() throws Exception {
        ResultType resultType = ResultType.FAILURE;

        // Successful exit indicates success.
        try {
          int exitCode = Integer.parseInt(
              new String(Files.readAllBytes(
                  getProjectFilesystem().resolve(
                      getPathToTestExitCode()))));
          if (exitCode == 0) {
            resultType = ResultType.SUCCESS;
          }
        } catch (IOException e) {
          // Any IO error means something went awry, so it's a failure.
          resultType = ResultType.FAILURE;
        }

        String testOutput = getProjectFilesystem().readFileIfItExists(
            getPathToTestOutput()).or("");
        String message = "";
        String stackTrace = "";
        String testName = "";
        if (resultType == ResultType.FAILURE && !testOutput.isEmpty()) {
          // We don't get any information on successful runs, but failures usually come with
          // some information. This code parses it.
          int firstNewline = testOutput.indexOf('\n');
          String firstLine = firstNewline == -1
                ? testOutput
                : testOutput.substring(0, firstNewline);
          // First line has format <Exception name>@<location>: <message>
          // Use <location> as test name, and <message> as message.
          Pattern firstLinePattern = Pattern.compile("^[^@]*@([^:]*): (.*)");
          Matcher m = firstLinePattern.matcher(firstLine);
          if (m.matches()) {
            testName = m.group(1);
            message = m.group(2);
          }
          // The whole output is actually a stack trace.
          stackTrace = testOutput;
        }

        TestResultSummary summary = new TestResultSummary(
            getBuildTarget().getShortName(),
            testName,
            resultType,
            /* time */ 0,
            message,
            stackTrace,
            testOutput,
            /* stderr */ "");

        return new TestResults(
            getBuildTarget(),
            ImmutableList.of(
                new TestCaseSummary(
                    "main",
                    ImmutableList.of(summary))
            ),
            contacts,
            FluentIterable.from(labels).transform(Functions.toStringFunction()).toSet());
      }
    };
  }

  @Override
  public ImmutableList<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      boolean isDryRun,
      boolean isShufflingTests,
      TestSelectorList testSelectorList,
      TestRule.TestReportingCallback testReportingCallback) {
    if (isDryRun) {
      return ImmutableList.of();
    } else {
      return ImmutableList.of(
          new MakeCleanDirectoryStep(getPathToTestOutputDirectory()),
          new DTestStep(
              getShellCommand(),
              getPathToTestExitCode(),
              getPathToTestOutput()));
    }
  }

  @Override
  public boolean runTestSeparately() {
    return false;
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }
}
