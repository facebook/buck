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

import com.facebook.buck.android.exopackage.AndroidDevice;
import com.facebook.buck.android.exopackage.AndroidDevicesHelper;
import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.XmlTestResultParser;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.PackagedResource;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.xml.sax.SAXException;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class AndroidInstrumentationTest extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements ExternalTestRunnerRule, HasRuntimeDeps, TestRule {

  private static final String TEST_RESULT_FILE = "test_result.xml";

  // TODO(#9027062): Migrate this to a PackagedResource so we don't make assumptions
  // about the ant build.
  private static final Path TESTRUNNER_CLASSES =
      Paths.get(
          System.getProperty(
              "buck.testrunner_classes", new File("ant-out/testrunner/classes").getAbsolutePath()));

  private final AndroidPlatformTarget androidPlatformTarget;

  private final Tool javaRuntimeLauncher;

  private final ImmutableSet<String> labels;

  private final ImmutableSet<String> contacts;

  private final HasInstallableApk apk;

  private final Optional<Long> testRuleTimeoutMs;
  private final PackagedResource ddmlibJar;
  private final PackagedResource kxml2Jar;
  private final PackagedResource guavaJar;
  private final PackagedResource toolsCommonJar;

  protected AndroidInstrumentationTest(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      BuildRuleParams params,
      HasInstallableApk apk,
      Set<String> labels,
      Set<String> contacts,
      Tool javaRuntimeLauncher,
      Optional<Long> testRuleTimeoutMs,
      PackagedResource ddmlibJar,
      PackagedResource kxml2Jar,
      PackagedResource guavaJar,
      PackagedResource toolsCommonJar) {
    super(buildTarget, projectFilesystem, params);
    this.androidPlatformTarget = androidPlatformTarget;
    this.apk = apk;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.labels = ImmutableSet.copyOf(labels);
    this.contacts = ImmutableSet.copyOf(contacts);
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.ddmlibJar = ddmlibJar;
    this.kxml2Jar = kxml2Jar;
    this.guavaJar = guavaJar;
    this.toolsCommonJar = toolsCommonJar;
  }

  private static AndroidDevice getSingleDevice(AndroidDevicesHelper adbHelper)
      throws InterruptedException {
    List<AndroidDevice> devices = adbHelper.getDevices(true);
    if (devices.isEmpty()) {
      throw new HumanReadableException("Expecting one android device/emulator to be attached.");
    } else if (devices.size() > 1) {
      throw new HumanReadableException(
          "Running android instrumentation tests with multiple devices is not supported.");
    }
    return devices.get(0);
  }

  private static String tryToExtractInstrumentationTestRunnerFromManifest(
      SourcePathResolver pathResolver, ApkInfo apkInfo) {
    Path pathToManifest = pathResolver.getAbsolutePath(apkInfo.getManifestPath());

    if (!Files.isRegularFile(pathToManifest)) {
      throw new HumanReadableException(
          "Manifest file %s does not exist, so could not extract package name.", pathToManifest);
    }

    try {
      return DefaultAndroidManifestReader.forPath(pathToManifest).getInstrumentationTestRunner();
    } catch (IOException e) {
      throw new HumanReadableException("Could not extract package name from %s", pathToManifest);
    }
  }

  private static String tryToExtractTargetPackageFromManifest(
      SourcePathResolver pathResolver, ApkInfo apkInfo) {
    Path pathToManifest = pathResolver.getAbsolutePath(apkInfo.getManifestPath());

    if (!Files.isRegularFile(pathToManifest)) {
      throw new HumanReadableException(
          "Manifest file %s does not exist, so could not extract package name.", pathToManifest);
    }

    try {
      return DefaultAndroidManifestReader.forPath(pathToManifest).getTargetPackage();
    } catch (IOException e) {
      throw new HumanReadableException("Could not extract package name from %s", pathToManifest);
    }
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
      BuildContext buildContext,
      TestReportingCallback testReportingCallback) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path pathToTestOutput = getPathToTestOutputDirectory();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), pathToTestOutput)));
    steps.add(new ApkInstallStep(buildContext.getSourcePathResolver(), apk));
    getApkUnderTest(apk)
        .ifPresent(
            apkUnderTest -> {
              steps.add(
                  new ApkInstallStep(
                      buildContext.getSourcePathResolver(),
                      ((AndroidInstrumentationApk) apk).getApkUnderTest()));
            });

    AndroidDevicesHelper adb = executionContext.getAndroidDevicesHelper().get();
    AndroidDevice device;
    try {
      device = getSingleDevice(adb);
    } catch (InterruptedException e) {
      throw new HumanReadableException("Unable to get connected device.");
    }

    steps.add(
        getInstrumentationStep(
            buildContext.getSourcePathResolver(),
            androidPlatformTarget.getAdbExecutable().toString(),
            Optional.of(getProjectFilesystem().resolve(pathToTestOutput)),
            Optional.of(device.getSerialNumber()),
            Optional.empty(),
            getFilterString(options),
            Optional.empty(),
            executionContext.isDebugEnabled(),
            executionContext.isCodeCoverageEnabled(),
            false));

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
      Optional<Path> apkUnderTestPath,
      boolean debugEnabled,
      boolean codeCoverageEnabled,
      boolean isExternalRun) {
    String packageName =
        AdbHelper.tryToExtractPackageNameFromManifest(pathResolver, apk.getApkInfo());
    String testRunner =
        tryToExtractInstrumentationTestRunnerFromManifest(pathResolver, apk.getApkInfo());
    String targetPackageName =
        tryToExtractTargetPackageFromManifest(pathResolver, apk.getApkInfo());

    String ddmlib = getPathForResourceJar(ddmlibJar);
    String kxml2 = getPathForResourceJar(kxml2Jar);
    String guava = getPathForResourceJar(guavaJar);
    String toolsCommon = getPathForResourceJar(toolsCommonJar);

    Optional<Path> exopackageSymlinkTreePath =
        getExopackageSymlinkTreePathIfNeeded(apk, isExternalRun);
    Optional<Path> apkUnderTestSymlinkTreePath =
        getApkUnderTest(apk)
            .flatMap(
                apkUnderTest ->
                    AndroidInstrumentationTest.getExopackageSymlinkTreePathIfNeeded(
                        apkUnderTest, isExternalRun));

    AndroidInstrumentationTestJVMArgs jvmArgs =
        AndroidInstrumentationTestJVMArgs.builder()
            .setApkUnderTestPath(apkUnderTestPath)
            .setApkUnderTestExopackageLocalDir(apkUnderTestSymlinkTreePath)
            .setPathToAdbExecutable(pathToAdbExecutable)
            .setDeviceSerial(deviceSerial)
            .setDirectoryForTestResults(directoryForTestResults)
            .setInstrumentationApkPath(instrumentationApkPath)
            .setExopackageLocalDir(exopackageSymlinkTreePath)
            .setTestPackage(packageName)
            .setTargetPackage(targetPackageName)
            .setCodeCoverageEnabled(codeCoverageEnabled)
            .setDebugEnabled(debugEnabled)
            .setTestRunner(testRunner)
            .setTestRunnerClasspath(TESTRUNNER_CLASSES)
            .setDdmlibJarPath(ddmlib)
            .setTestFilter(classFilterArg)
            .setKxmlJarPath(kxml2)
            .setGuavaJarPath(guava)
            .setAndroidToolsCommonJarPath(toolsCommon)
            .build();

    return new InstrumentationStep(
        getProjectFilesystem(),
        javaRuntimeLauncher.getCommandPrefix(pathResolver),
        jvmArgs,
        testRuleTimeoutMs);
  }

  private String getPathForResourceJar(PackagedResource packagedResource) {
    ProjectFilesystem filesystem = this.getProjectFilesystem();
    Path relativePath = PathSourcePath.of(filesystem, packagedResource.get()).getRelativePath();
    return filesystem.resolve(relativePath).toString();
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "__android_instrumentation_test_%s_output__");
  }

  private static TestCaseSummary getTestClassAssumedSummary(String buildTargetName) {
    return new TestCaseSummary(
        buildTargetName,
        ImmutableList.of(
            new TestResultSummary(
                buildTargetName,
                "none",
                ResultType.ASSUMPTION_VIOLATION,
                0L,
                "No tests run",
                null,
                null,
                null)));
  }

  private static TestCaseSummary getTestApkCrashedSummary(String buildTargetName) {
    return new TestCaseSummary(
        buildTargetName,
        ImmutableList.of(
            new TestResultSummary(
                buildTargetName,
                "none",
                ResultType.FAILURE,
                0L,
                "The APK crashed while trying to set up the test runner. No tests ran",
                null,
                null,
                null)));
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext context, SourcePathResolver pathResolver, boolean isUsingTestSelectors) {
    return () -> {
      ImmutableList.Builder<TestCaseSummary> summaries = ImmutableList.builder();
      AndroidDevice device;
      AndroidDevicesHelper adbHelper = context.getAndroidDevicesHelper().get();
      try {
        device = getSingleDevice(adbHelper);
      } catch (InterruptedException e) {
        device = null;
      }
      if (device == null) {
        summaries.add(getTestClassAssumedSummary(getBuildTarget().getFullyQualifiedName()));
      } else {
        Path testResultPath =
            getProjectFilesystem()
                .resolve(getPathToTestOutputDirectory().resolve(TEST_RESULT_FILE));
        summaries.addAll(
            readSummariesFromPath(
                getBuildTarget().getFullyQualifiedName(), testResultPath, device));
      }
      return TestResults.of(
          getBuildTarget(),
          summaries.build(),
          contacts,
          labels.stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()));
    };
  }

  @VisibleForTesting
  static List<TestCaseSummary> readSummariesFromPath(
      String buildTargetName, Path testResultPath, AndroidDevice device)
      throws IOException, SAXException {
    List<TestCaseSummary> summaries = new ArrayList<>();
    if (Files.exists(testResultPath)) {
      summaries.addAll(XmlTestResultParser.parseAndroid(testResultPath, device.getSerialNumber()));
    } else {
      summaries.add(getTestApkCrashedSummary(buildTargetName));
    }
    return summaries;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToTestOutputDirectory());
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

  /**
   * Called in order to perform setup for external tests. We use this opportunity to lay down a
   * symlink tree for the exopackage directory.
   */
  @Override
  public void onPreTest(BuildContext buildContext) {
    new ExopackageSymlinkTreeStep(apk, getApkUnderTest(apk), buildContext).executeStep();
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {
    Optional<Path> apkUnderTestPath =
        getApkUnderTest(apk)
            .map(
                apkUnderTest ->
                    buildContext
                        .getSourcePathResolver()
                        .getAbsolutePath(apkUnderTest.getApkInfo().getApkPath()));
    Optional<Path> instrumentationApkPath =
        Optional.of(
            buildContext.getSourcePathResolver().getAbsolutePath(apk.getApkInfo().getApkPath()));
    InstrumentationStep step =
        getInstrumentationStep(
            buildContext.getSourcePathResolver(),
            androidPlatformTarget.getAdbExecutable().toString(),
            Optional.empty(),
            Optional.empty(),
            instrumentationApkPath,
            Optional.empty(),
            apkUnderTestPath,
            executionContext.isDebugEnabled(),
            executionContext.isCodeCoverageEnabled(),
            true);

    ImmutableList<Path> requiredPaths =
        getRequiredPaths(apk, instrumentationApkPath, apkUnderTestPath);

    return ExternalTestRunnerTestSpec.builder()
        .setCwd(getProjectFilesystem().getRootPath())
        .setTarget(getBuildTarget())
        .setType("android_instrumentation")
        .setCommand(step.getShellCommandInternal(executionContext))
        .setLabels(getLabels())
        .setContacts(getContacts())
        .setRequiredPaths(requiredPaths)
        .build();
  }

  /**
   * @return a list of paths which must be materialized on disk before an external testrunner can
   *     execute the test.
   */
  protected static ImmutableList<Path> getRequiredPaths(
      HasInstallableApk apkInstance,
      Optional<Path> instrumentationApkPath,
      Optional<Path> apkUnderTestPath) {
    Optional<Path> exopackageSymlinkTreePath =
        getExopackageSymlinkTreePathIfNeeded(apkInstance, true);
    Optional<Path> apkUnderTestSymlinkTreePath =
        getApkUnderTest(apkInstance)
            .flatMap(
                apkUnderTest ->
                    AndroidInstrumentationTest.getExopackageSymlinkTreePathIfNeeded(
                        apkUnderTest, true));
    return ImmutableList.<Optional<Path>>builder().add(apkUnderTestPath).add(instrumentationApkPath)
        .add(exopackageSymlinkTreePath).add(apkUnderTestSymlinkTreePath).build().stream()
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    Stream.Builder<BuildTarget> builder = Stream.builder();
    builder.add(apk.getBuildTarget());
    getApkUnderTest(apk).map(HasInstallableApk::getBuildTarget).ifPresent(builder::add);
    return builder.build();
  }

  /** @return the test apk */
  public HasInstallableApk getApk() {
    return apk;
  }

  /** @return the apk under test, if any */
  private static Optional<HasInstallableApk> getApkUnderTest(HasInstallableApk apkInstance) {
    if (apkInstance instanceof AndroidInstrumentationApk) {
      return Optional.of(((AndroidInstrumentationApk) apkInstance).getApkUnderTest());
    } else {
      return Optional.empty();
    }
  }

  /**
   * @param apk the apk to install
   * @return A Path pointing to the apk's exopackage layout dir if the apk supports exopackage and
   *     the test is run with an external runner. When running with the internal runner, the
   *     exopackage symlink tree is not necessary because we use Buck's internal exopackage support
   *     to do the installation.
   */
  private static Optional<Path> getExopackageSymlinkTreePathIfNeeded(
      HasInstallableApk apk, boolean isExternalRun) {
    Optional<Path> exopackageSymlinkTreePath = Optional.empty();
    // We only need the exo-dir if the apk supports it and we're preparing for an external runner.
    if (isExternalRun && ExopackageInstaller.exopackageEnabled(apk.getApkInfo())) {
      ProjectFilesystem filesystem = apk.getProjectFilesystem();
      exopackageSymlinkTreePath =
          Optional.of(
              filesystem
                  .getRootPath()
                  .resolve(
                      ExopackageSymlinkTreeStep.getExopackageSymlinkTreePath(
                          apk.getBuildTarget(), filesystem)));
    }
    return exopackageSymlinkTreePath;
  }
}
