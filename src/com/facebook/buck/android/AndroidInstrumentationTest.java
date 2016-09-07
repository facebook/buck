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

import com.android.ddmlib.IDevice;
import com.facebook.buck.jvm.java.JavaRuntimeLauncher;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.XmlTestResultParser;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.PackagedResource;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.Callable;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class AndroidInstrumentationTest extends AbstractBuildRule
    implements ExternalTestRunnerRule, HasRuntimeDeps, TestRule {

  private static final String TEST_RESULT_FILE = "test_result.xml";

  // TODO(#9027062): Migrate this to a PackagedResource so we don't make assumptions
  // about the ant build.
  private static final Path TESTRUNNER_CLASSES =
      Paths.get(
          System.getProperty(
              "buck.testrunner_classes",
              new File("build/testrunner/classes").getAbsolutePath()));

  @AddToRuleKey
  private final JavaRuntimeLauncher javaRuntimeLauncher;

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
      JavaRuntimeLauncher javaRuntimeLauncher,
      Optional<Long> testRuleTimeoutMs) {
    super(params, resolver);
    this.apk = apk;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
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
  public boolean supportsStreamingTests() {
    return false;
  }

  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      TestReportingCallback testReportingCallback) {
    Preconditions.checkArgument(executionContext.getAdbOptions().isPresent());

    if (executionContext.getAdbOptions().get().isMultiInstallModeEnabled()) {
      throw new HumanReadableException(
          "Running android instrumentation tests with multiple devices is not supported.");
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path pathToTestOutput = getPathToTestOutputDirectory();
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), pathToTestOutput));
    steps.add(new ApkInstallStep(apk));
    if (apk instanceof AndroidInstrumentationApk) {
      steps.add(new ApkInstallStep(
              ((AndroidInstrumentationApk) apk).getApkUnderTest()));
    }

    AdbHelper adb = AdbHelper.get(executionContext, true);
    IDevice device;
    try {
      device = adb.getSingleDevice();
    } catch (InterruptedException e) {
      throw new HumanReadableException("Unable to get connected device.");
    }

    steps.add(getInstrumentationStep(
            executionContext.getPathToAdbExecutable(),
            Optional.of(getProjectFilesystem().resolve(pathToTestOutput)),
            Optional.of(device.getSerialNumber()),
            Optional.<Path>absent(),
            Optional.<Path>absent()));

    return steps.build();
  }

  private InstrumentationStep getInstrumentationStep(
      String pathToAdbExecutable,
      Optional<Path> directoryForTestResults,
      Optional<String> deviceSerial,
      Optional<Path> instrumentationApkPath,
      Optional<Path> apkUnderTestPath) {
    String packageName = AdbHelper.tryToExtractPackageNameFromManifest(apk);
    String testRunner = AdbHelper.tryToExtractInstrumentationTestRunnerFromManifest(apk);

    String ddmlib = new PathSourcePath(
        this.getProjectFilesystem(),
        AndroidInstrumentationTest.class + "/ddmlib.jar",
        new PackagedResource(
            this.getProjectFilesystem(),
            AndroidInstrumentationTest.class,
            "ddmlib.jar")).getRelativePath().toString();

    String kxml2 = new PathSourcePath(
        this.getProjectFilesystem(),
        AndroidInstrumentationTest.class + "/kxml2.jar",
        new PackagedResource(
            this.getProjectFilesystem(),
            AndroidInstrumentationTest.class,
            "kxml2.jar")).getRelativePath().toString();

    AndroidInstrumentationTestJVMArgs jvmArgs = AndroidInstrumentationTestJVMArgs.builder()
        .setApkUnderTestPath(apkUnderTestPath)
        .setPathToAdbExecutable(pathToAdbExecutable)
        .setDeviceSerial(deviceSerial)
        .setDirectoryForTestResults(directoryForTestResults)
        .setInstrumentationApkPath(instrumentationApkPath)
        .setTestPackage(packageName)
        .setTestRunner(testRunner)
        .setTestRunnerClasspath(TESTRUNNER_CLASSES)
        .setDdmlibJarPath(ddmlib)
        .setKxmlJarPath(kxml2)
        .build();

    return new InstrumentationStep(
        getProjectFilesystem(), javaRuntimeLauncher, jvmArgs, testRuleTimeoutMs);
  }

  @Override
  public boolean hasTestResultFiles() {
    Path testResultPath = getProjectFilesystem().resolve(
        getPathToTestOutputDirectory().resolve(TEST_RESULT_FILE));
    return testResultPath.toFile().exists();
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(),
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
      final boolean isUsingTestSelectors) {
    return new Callable<TestResults>() {
      @Override
      public TestResults call() throws Exception {
        final ImmutableList.Builder<TestCaseSummary> summaries = ImmutableList.builder();
        IDevice device;
        AdbHelper adbHelper = AdbHelper.get(context, true);
        try {
          device = adbHelper.getSingleDevice();
        } catch (InterruptedException e) {
          device = null;
        }
        if (device == null) {
          summaries.add(getTestClassAssumedSummary());
        } else {
          Path testResultPath = getProjectFilesystem().resolve(
              getPathToTestOutputDirectory().resolve(TEST_RESULT_FILE));
          summaries.addAll(
              XmlTestResultParser.parseAndroid(testResultPath, device.getSerialNumber()));
        }
        return TestResults.of(
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

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext, TestRunningOptions testRunningOptions) {
    Optional<Path> apkUnderTestPath = Optional.absent();
    if (apk instanceof AndroidInstrumentationApk) {
      apkUnderTestPath = Optional.of(toPath(((AndroidInstrumentationApk) apk).getApkUnderTest()));
    }
    InstrumentationStep step = getInstrumentationStep(
        executionContext.getPathToAdbExecutable(),
        Optional.<Path>absent(),
        Optional.<String>absent(),
        Optional.of(toPath(apk)),
        apkUnderTestPath);

    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("android_instrumentation")
        .setCommand(step.getShellCommandInternal(executionContext))
        .setLabels(getLabels())
        .setContacts(getContacts())
        .build();
  }

  private static Path toPath(InstallableApk apk) {
    return apk.getProjectFilesystem().resolve(
        apk.getApkPath());
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    builder.add(apk);

    if (apk instanceof ApkGenrule) {
      builder.add(((ApkGenrule) apk).getInstallableApk());
    }

    return builder.build();
  }
}
