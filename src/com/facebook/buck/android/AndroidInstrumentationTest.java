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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
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
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.PackagedResource;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class AndroidInstrumentationTest extends AbstractBuildRule
    implements ExternalTestRunnerRule, HasRuntimeDeps, TestRule {

  private static final String TEST_RESULT_FILE = "test_result.xml";

  // TODO(#9027062): Migrate this to a PackagedResource so we don't make assumptions
  // about the ant build.
  private static final Path TESTRUNNER_CLASSES =
      Paths.get(
          System.getProperty(
              "buck.testrunner_classes", new File("build/testrunner/classes").getAbsolutePath()));

  @AddToRuleKey private final JavaRuntimeLauncher javaRuntimeLauncher;

  private final ImmutableSet<String> labels;

  private final ImmutableSet<String> contacts;

  private final HasInstallableApk apk;

  private final Optional<Long> testRuleTimeoutMs;

  protected AndroidInstrumentationTest(
      BuildRuleParams params,
      HasInstallableApk apk,
      Set<String> labels,
      Set<String> contacts,
      JavaRuntimeLauncher javaRuntimeLauncher,
      Optional<Long> testRuleTimeoutMs) {
    super(params);
    this.apk = apk;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.labels = ImmutableSet.copyOf(labels);
    this.contacts = ImmutableSet.copyOf(contacts);
    this.testRuleTimeoutMs = testRuleTimeoutMs;
  }

  @Override
  public ImmutableSet<String> getLabels() {
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
      SourcePathResolver pathResolver,
      TestReportingCallback testReportingCallback) {
    Preconditions.checkArgument(executionContext.getAdbOptions().isPresent());

    if (executionContext.getAdbOptions().get().isMultiInstallModeEnabled()) {
      throw new HumanReadableException(
          "Running android instrumentation tests with multiple devices is not supported.");
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path pathToTestOutput = getPathToTestOutputDirectory();
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), pathToTestOutput));
    steps.add(new ApkInstallStep(pathResolver, apk));
    if (apk instanceof AndroidInstrumentationApk) {
      steps.add(
          new ApkInstallStep(pathResolver, ((AndroidInstrumentationApk) apk).getApkUnderTest()));
    }

    AdbHelper adb = AdbHelper.get(executionContext, true);
    IDevice device;
    try {
      device = adb.getSingleDevice();
    } catch (InterruptedException e) {
      throw new HumanReadableException("Unable to get connected device.");
    }

    steps.add(
        getInstrumentationStep(
            pathResolver,
            executionContext.getPathToAdbExecutable(),
            Optional.of(getProjectFilesystem().resolve(pathToTestOutput)),
            Optional.of(device.getSerialNumber()),
            Optional.empty(),
            getFilterString(options),
            Optional.empty()));

    return steps.build();
  }

  @VisibleForTesting
  static Optional<String> getFilterString(TestRunningOptions options) {
    List<String> rawSelectors = options.getTestSelectorList().getRawSelectors();
    if (rawSelectors.size() == 1) { // multiple selectors not supported
      return Optional.of(stripRegexs(rawSelectors.get(0)));
    }
    return Optional.empty();
  }

  /**
   * Buck adds some regex support to TestSelectors. Instrumentation tests don't support that so
   * let's strip that and make it a plan Class#method string filter.
   */
  private static String stripRegexs(String selector) {
    return selector.replaceAll("[$]", "").replaceAll("#$", "");
  }

  private InstrumentationStep getInstrumentationStep(
      SourcePathResolver pathResolver,
      String pathToAdbExecutable,
      Optional<Path> directoryForTestResults,
      Optional<String> deviceSerial,
      Optional<Path> instrumentationApkPath,
      Optional<String> classFilterArg,
      Optional<Path> apkUnderTestPath) {
    String packageName =
        AdbHelper.tryToExtractPackageNameFromManifest(pathResolver, apk.getApkInfo());
    String testRunner =
        AdbHelper.tryToExtractInstrumentationTestRunnerFromManifest(pathResolver, apk.getApkInfo());

    String ddmlib = getPathForResourceJar("ddmlib.jar");
    String kxml2 = getPathForResourceJar("kxml2.jar");
    String guava = getPathForResourceJar("guava.jar");
    String toolsCommon = getPathForResourceJar("android-tools-common.jar");

    AndroidInstrumentationTestJVMArgs jvmArgs =
        AndroidInstrumentationTestJVMArgs.builder()
            .setApkUnderTestPath(apkUnderTestPath)
            .setPathToAdbExecutable(pathToAdbExecutable)
            .setDeviceSerial(deviceSerial)
            .setDirectoryForTestResults(directoryForTestResults)
            .setInstrumentationApkPath(instrumentationApkPath)
            .setTestPackage(packageName)
            .setTestRunner(testRunner)
            .setTestRunnerClasspath(TESTRUNNER_CLASSES)
            .setDdmlibJarPath(ddmlib)
            .setTestFilter(classFilterArg)
            .setKxmlJarPath(kxml2)
            .setGuavaJarPath(guava)
            .setAndroidToolsCommonJarPath(toolsCommon)
            .build();

    return new InstrumentationStep(
        getProjectFilesystem(), javaRuntimeLauncher, jvmArgs, testRuleTimeoutMs);
  }

  private String getPathForResourceJar(String jarName) {
    return new PathSourcePath(
            this.getProjectFilesystem(),
            AndroidInstrumentationTest.class + "/" + jarName,
            new PackagedResource(
                this.getProjectFilesystem(), AndroidInstrumentationTest.class, jarName))
        .getRelativePath()
        .toString();
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "__android_instrumentation_test_%s_output__");
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
      final ExecutionContext context, final boolean isUsingTestSelectors) {
    return () -> {
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
        Path testResultPath =
            getProjectFilesystem()
                .resolve(getPathToTestOutputDirectory().resolve(TEST_RESULT_FILE));
        summaries.addAll(
            XmlTestResultParser.parseAndroid(testResultPath, device.getSerialNumber()));
      }
      return TestResults.of(
          getBuildTarget(),
          summaries.build(),
          contacts,
          labels.stream().map(Object::toString).collect(MoreCollectors.toImmutableSet()));
    };
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), getPathToTestOutputDirectory());
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    return steps.build();
  }

  @Override
  public boolean runTestSeparately() {
    return false;
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      SourcePathResolver pathResolver) {
    Optional<Path> apkUnderTestPath = Optional.empty();
    if (apk instanceof AndroidInstrumentationApk) {
      apkUnderTestPath =
          Optional.of(
              pathResolver.getAbsolutePath(
                  ((AndroidInstrumentationApk) apk).getApkUnderTest().getApkInfo().getApkPath()));
    }
    InstrumentationStep step =
        getInstrumentationStep(
            pathResolver,
            executionContext.getPathToAdbExecutable(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(pathResolver.getAbsolutePath(apk.getApkInfo().getApkPath())),
            Optional.empty(),
            apkUnderTestPath);

    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("android_instrumentation")
        .setCommand(step.getShellCommandInternal(executionContext))
        .setLabels(getLabels())
        .setContacts(getContacts())
        .build();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    Stream.Builder<BuildTarget> builder = Stream.builder();
    builder.add(apk.getBuildTarget());

    if (apk instanceof ApkGenrule) {
      builder.add(((ApkGenrule) apk).getInstallableApk().getBuildTarget());
    }

    return builder.build();
  }
}
