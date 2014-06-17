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

package com.facebook.buck.cli;

import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.rules.Label;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.base.Optional;

import org.kohsuke.args4j.Option;

import java.util.Set;

import javax.annotation.Nullable;

public class TestCommandOptions extends BuildCommandOptions {

  public static final String USE_RESULTS_CACHE = "use_results_cache";

  @Option(name = "--all",
          usage =
              "Whether all of the tests should be run. " +
              "If no targets are given, --all is implied")
  private boolean all = false;

  @Option(name = "--code-coverage", usage = "Whether code coverage information will be generated.")
  private boolean isCodeCoverageEnabled = false;

  @Option(name = "--debug",
          usage = "Whether the test will start suspended with a JDWP debug port of 5005")
  private boolean isDebugEnabled = false;

  @Option(name = "--xml", usage = "Where to write test output as XML.")
  @Nullable
  private String pathToXmlTestOutput = null;

  @Option(name = "--jacoco",
          usage = "Whether jacoco should be used for code coverage analysis or emma.")
  private boolean isJaccoEnabled = false;

  @Option(name = "--no-results-cache", usage = "Whether to use cached test results.")
  private boolean isResultsCacheDisabled = false;

  @Option(
      name = "--ignore-when-dependencies-fail",
      aliases = {"-i"},
      usage =
          "Ignore test failures for libraries if they depend on other libraries " +
          "that aren't passing their tests.  " +
          "For example, if java_library A depends on B, " +
          "and they are tested respectively by T1 and T2 and both of those tests fail, " +
          "only print the error for T2.")
  private boolean isIgnoreFailingDependencies = false;

  @Option(
      name = "--dry-run",
      usage = "Print tests that match the given command line options, but don't run them.")
  private boolean isDryRun;

  @Option(
      name = "--one-time-output",
      usage =
          "Put test-results in a unique, one-time output directory.  " +
          "This allows multiple tests to be run in parallel without interfering with each other, " +
          "but at the expense of being unable to use cached results.  " +
          "WARNING: this is experimental, and only works for Java tests!")
  private boolean isUsingOneTimeOutput;

  @AdditionalOptions
  private TargetDeviceOptions targetDeviceOptions;

  @AdditionalOptions
  private TestSelectorOptions testSelectorOptions;

  @AdditionalOptions
  private TestLabelOptions testLabelOptions;

  public TestCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);

    setUseResultsCacheFromConfig(buckConfig);
  }

  public boolean isRunAllTests() {
    return all || getArguments().isEmpty();
  }

  @Nullable
  public String getPathToXmlTestOutput() {
    return pathToXmlTestOutput;
  }

  public Optional<DefaultJavaPackageFinder> getJavaPackageFinder() {
    return Optional.fromNullable(getBuckConfig().createDefaultJavaPackageFinder());
  }

  @Override
  public boolean isCodeCoverageEnabled() {
    return isCodeCoverageEnabled;
  }

  @Override
  public boolean isJacocoEnabled() {
    return isJaccoEnabled;
  }

  private void setUseResultsCacheFromConfig(BuckConfig buckConfig) {
    // The command line option is a negative one, hence the slightly confusing logic.
    boolean isUseResultsCache = buckConfig.getBooleanValue("test", USE_RESULTS_CACHE, true);
    isResultsCacheDisabled = !isUseResultsCache;
  }

  public boolean isResultsCacheEnabled() {
    // The option is negative (--no-X) but we prefer to reason about positives, in the code.
    return !isResultsCacheDisabled;
  }

  @Override
  public boolean isDebugEnabled() {
    return isDebugEnabled;
  }

  public boolean isUsingOneTimeOutputDirectories() {
    return isUsingOneTimeOutput;
  }

  public boolean isIgnoreFailingDependencies() {
    return isIgnoreFailingDependencies;
  }

  public Optional<TargetDevice> getTargetDeviceOptional() {
    return targetDeviceOptions.getTargetDeviceOptional();
  }

  public TestSelectorList getTestSelectorList() {
    return testSelectorOptions.getTestSelectorList();
  }

  public boolean shouldExplainTestSelectorList() {
    return testSelectorOptions.shouldExplain();
  }

  public boolean isDryRun() {
    return isDryRun;
  }

  public boolean isMatchedByLabelOptions(Set<Label> labels) {
    return testLabelOptions.isMatchedByLabelOptions(getBuckConfig(), labels);
  }

  public boolean shouldExcludeWin() {
    return testLabelOptions.shouldExcludeWin();
  }
}
