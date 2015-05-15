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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.zip.UnzipStep;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class AppleTest extends NoopBuildRule implements TestRule {

  @AddToRuleKey
  private final Optional<Path> xctoolPath;

  @AddToRuleKey
  private final String sdkName;

  @AddToRuleKey
  private final String simulatorName;

  @AddToRuleKey
  private final String arch;

  @AddToRuleKey
  private final BuildRule testBundle;

  @AddToRuleKey
  private final Optional<AppleBundle> testHostApp;

  private final ImmutableSet<String> contacts;
  private final ImmutableSet<Label> labels;

  private final Path testBundleDirectory;
  private final Path testHostAppDirectory;
  private final Path testOutputPath;

  AppleTest(
      Optional<Path> xctoolPath,
      String sdkName,
      String arch,
      String simulatorName,
      BuildRuleParams params,
      SourcePathResolver resolver,
      BuildRule testBundle,
      Optional<AppleBundle> testHostApp,
      String testBundleExtension,
      ImmutableSet<String> contacts,
      ImmutableSet<Label> labels) {
    super(params, resolver);
    this.xctoolPath = xctoolPath;
    this.sdkName = sdkName;
    this.simulatorName = simulatorName;
    this.arch = arch;
    this.testBundle = testBundle;
    this.testHostApp = testHostApp;
    this.contacts = contacts;
    this.labels = labels;
    // xctool requires the extension to be present to determine whether the test is ocunit or xctest
    this.testBundleDirectory = BuildTargets.getScratchPath(
        params.getBuildTarget(),
        "__test_bundle_%s__." + testBundleExtension);
    this.testHostAppDirectory = BuildTargets.getScratchPath(
        params.getBuildTarget(),
        "__test_host_app_%s__.app");
    this.testOutputPath = getPathToTestOutputDirectory().resolve("test-output.json");
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
    // Apple tests always express a rule -> test dependency, not the other way
    // around.
    return ImmutableSet.of();
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    return executionContext.getProjectFilesystem().exists(testOutputPath);
  }

  @Override
  public ImmutableList<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      boolean isDryRun,
      boolean isShufflingTests,
      TestSelectorList testSelectorList) {
    if (!xctoolPath.isPresent()) {
      throw new HumanReadableException(
          "Set xctool_path = /path/to/xctool in the [apple] section of .buckconfig " +
          "to run this test");
    }
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path resolvedTestBundleDirectory = executionContext.getProjectFilesystem().resolve(
        testBundleDirectory);
    steps.add(new MakeCleanDirectoryStep(resolvedTestBundleDirectory));
    steps.add(new UnzipStep(testBundle.getPathToOutputFile(), resolvedTestBundleDirectory));

    Path pathToTestOutput = executionContext.getProjectFilesystem().resolve(
        getPathToTestOutputDirectory());
    steps.add(new MakeCleanDirectoryStep(pathToTestOutput));

    Path resolvedTestOutputPath = executionContext.getProjectFilesystem().resolve(
        testOutputPath);

    ImmutableSet.Builder<Path> logicTestPathsBuilder = ImmutableSet.builder();
    ImmutableMap.Builder<Path, Path> appTestPathsToHostAppsBuilder = ImmutableMap.builder();
    if (testHostApp.isPresent()) {
      Path resolvedTestHostAppDirectory = executionContext.getProjectFilesystem().resolve(
          testHostAppDirectory);
      steps.add(new MakeCleanDirectoryStep(resolvedTestHostAppDirectory));
      steps.add(
          new UnzipStep(testHostApp.get().getPathToOutputFile(), resolvedTestHostAppDirectory));

      appTestPathsToHostAppsBuilder.put(
          resolvedTestBundleDirectory,
          resolvedTestHostAppDirectory.resolve(
              testHostApp.get().getUnzippedOutputFilePathToBinary()));
    } else {
      logicTestPathsBuilder.add(resolvedTestBundleDirectory);
    }

    steps.add(
        new XctoolRunTestsStep(
            xctoolPath.get(),
            arch,
            sdkName,
            simulatorName,
            logicTestPathsBuilder.build(),
            appTestPathsToHostAppsBuilder.build(),
            resolvedTestOutputPath));

    return steps.build();
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      final ExecutionContext executionContext,
      boolean isUsingTestSelectors,
      boolean isDryRun) {
    return new Callable<TestResults>() {
      @Override
      public TestResults call() throws Exception {
        Path resolvedOutputPath = executionContext.getProjectFilesystem().resolve(testOutputPath);
        try (BufferedReader reader =
               Files.newBufferedReader(resolvedOutputPath, StandardCharsets.UTF_8)) {
          return new TestResults(
            getBuildTarget(),
            XctoolOutputParsing.parseOutputFromReader(reader),
            contacts,
            FluentIterable.from(labels).transform(Functions.toStringFunction()).toSet());
        }
      }
    };
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    // TODO(user): Refactor the JavaTest implementation; this is identical.

    List<String> pathsList = new ArrayList<>();
    pathsList.add(getBuildTarget().getBaseNameWithSlash());
    pathsList.add(
        String.format("__apple_test_%s_output__", getBuildTarget().getShortNameAndFlavorPostfix()));

    // Putting the one-time test-sub-directory below the usual directory has the nice property that
    // doing a test run without "--one-time-output" will tidy up all the old one-time directories!
    String subdir = BuckConstant.oneTimeTestSubdirectory;
    if (subdir != null && !subdir.isEmpty()) {
      pathsList.add(subdir);
    }

    String[] pathsArray = pathsList.toArray(new String[pathsList.size()]);
    return Paths.get(BuckConstant.GEN_DIR, pathsArray);
  }

  @Override
  public boolean runTestSeparately() {
    // Tests which run in the simulator must run separately from all other tests;
    // there's a 20 second timeout hard-coded in the iOS Simulator SpringBoard which
    // is hit any time the host is overloaded.
    return testHostApp.isPresent();
  }
}
