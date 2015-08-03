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

package com.facebook.buck.android;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.XmlTestResultParser;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import com.android.ddmlib.IDevice;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class AndroidInstrumentationTest extends AbstractBuildRule
    implements TestRule {

  @AddToRuleKey
  private ImmutableSet<BuildRule> sourceUnderTest;

  private final ImmutableSet<Label> labels;

  private final ImmutableSet<String> contacts;

  private final InstallableApk apk;

  private final Optional<Long> testRuleTimeoutMs;

  protected AndroidInstrumentationTest(
      BuildRuleParams params,
      SourcePathResolver resolver,
      InstallableApk apk,
      Set<Label> labels,
      Set<String> contacts,
      ImmutableSet<BuildRule> sourceUnderTest,
      Optional<Long> testRuleTimeoutMs) {
    super(params, resolver);
    this.apk = apk;
    this.sourceUnderTest = sourceUnderTest;
    this.labels = ImmutableSet.copyOf(labels);
    this.contacts = ImmutableSet.copyOf(contacts);
    this.testRuleTimeoutMs = testRuleTimeoutMs;
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
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public ImmutableList<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      boolean isDryRun,
      boolean isShufflingTests,
      TestSelectorList testSelectorList,
      TestRule.TestReportingCallback testReportingCallback) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path pathToTestOutput = getPathToTestOutputDirectory();
    steps.add(new MakeCleanDirectoryStep(pathToTestOutput));
    steps.add(new ApkInstallStep(apk));
    if (apk instanceof AndroidInstrumentationApk) {
      steps.add(new ApkInstallStep(
              ((AndroidInstrumentationApk) apk).getApkUnderTest()));
    }
    steps.add(new InstrumentationStep(
            apk,
            pathToTestOutput,
            testRuleTimeoutMs));

    return steps.build();
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext context) {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    List<IDevice> devices;
    AdbHelper adbHelper = AdbHelper.get(context, true);
    try {
      devices = adbHelper.getDevices(true);
    } catch (InterruptedException e) {
      devices = Lists.newArrayList();
    }
    // It might happen that a new device is attached when re-running the tests,
    // in which case we want to re-run the tests with the new device included.
    for (IDevice device : devices) {
      Path testResultPath = filesystem.getPathForRelativePath(
          getPathToTestOutputDirectory().resolve(
              BuckXmlTestRunListener.TEST_RESULT_FILE_PREFIX +
              device.getSerialNumber() +
              BuckXmlTestRunListener.TEST_RESULT_FILE_SUFFIX));
      if (!testResultPath.toFile().exists()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(
        getBuildTarget(),
        "__android_instrumentation_test_%s_output__");
  }

  private TestCaseSummary getTestClassAssumedSummary() {
    return new TestCaseSummary(
        getBuildTarget().getFullyQualifiedName(),
        ImmutableList.of(
            new TestResultSummary(
                getBuildTarget().getFullyQualifiedName(),
                "none",
                ResultType.ASSUMPTION_VIOLATION,
                0L,
                "No tests run",
                null,
                null,
                null)));
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      final ExecutionContext context,
      final boolean isUsingTestSelectors,
      final boolean isDryRun) {
    return new Callable<TestResults>() {
      @Override
      public TestResults call() throws Exception {
        final ImmutableList.Builder<TestCaseSummary> summaries = ImmutableList.builder();
        ProjectFilesystem filesystem = context.getProjectFilesystem();
        List<IDevice> devices;
        AdbHelper adbHelper = AdbHelper.get(context, true);
        try {
          devices = adbHelper.getDevices(true);
        } catch (InterruptedException e) {
          devices = Lists.newArrayList();
        }
        if (devices.isEmpty()) {
          summaries.add(getTestClassAssumedSummary());
        }

        for (IDevice device : devices) {
          Path testResultPath = filesystem.getPathForRelativePath(
              getPathToTestOutputDirectory().resolve(
                  BuckXmlTestRunListener.TEST_RESULT_FILE_PREFIX +
                  device.getSerialNumber() +
                  BuckXmlTestRunListener.TEST_RESULT_FILE_SUFFIX));
          summaries.add(
              XmlTestResultParser.parseAndroid(testResultPath, device.getSerialNumber()));
        }
        return new TestResults(
            getBuildTarget(),
            summaries.build(),
            contacts,
            FluentIterable.from(labels).transform(Functions.toStringFunction()).toSet());
      }
    };
  }

  @Override
  public Path getPathToOutput() {
    return getPathToTestOutputDirectory();
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    return steps.build();
  }

  @Override
  public boolean runTestSeparately() {
    return false;
  }

}
