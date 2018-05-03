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

import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.test.rule.ExternalTestRunnerRule;
import com.facebook.buck.core.test.rule.ExternalTestRunnerTestSpec;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class AppleTest extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements TestRule, HasRuntimeDeps, ExternalTestRunnerRule {

  private final Optional<SourcePath> xctool;

  private Optional<Long> xctoolStutterTimeout;

  private final Tool xctest;

  private final boolean useXctest;

  private final String platformName;

  private final Optional<String> defaultDestinationSpecifier;
  private final Optional<ImmutableMap<String, String>> destinationSpecifier;

  private final AppleBundle testBundle;

  @AddToRuleKey private final Optional<AppleBundle> testHostApp;

  private final Optional<AppleBundle> uiTestTargetApp;

  private final ImmutableSet<String> contacts;
  private final ImmutableSet<String> labels;

  private final boolean runTestSeparately;

  @AddToRuleKey private final boolean isUiTest;

  private final Path testOutputPath;
  private final Path testLogsPath;

  private final Optional<Either<SourcePath, String>> snapshotReferenceImagesPath;

  private Optional<Long> testRuleTimeoutMs;

  private Optional<AppleTestXctoolStdoutReader> xctoolStdoutReader;
  private Optional<AppleTestXctestOutputReader> xctestOutputReader;

  private final String testLogDirectoryEnvironmentVariable;
  private final String testLogLevelEnvironmentVariable;
  private final String testLogLevel;
  private final Optional<ImmutableMap<String, String>> testSpecificEnvironmentVariables;

  /**
   * Absolute path to xcode developer dir.
   *
   * <p>Should not be added to rule key.
   */
  private final AppleDeveloperDirectoryForTestsProvider appleDeveloperDirectoryForTestsProvider;

  private static class AppleTestXctoolStdoutReader
      implements XctoolRunTestsStep.StdoutReadingCallback {

    private final TestCaseSummariesBuildingXctoolEventHandler xctoolEventHandler;

    public AppleTestXctoolStdoutReader(TestRule.TestReportingCallback testReportingCallback) {
      this.xctoolEventHandler =
          new TestCaseSummariesBuildingXctoolEventHandler(testReportingCallback);
    }

    @Override
    public void readStdout(InputStream stdout) throws IOException {
      try (InputStreamReader stdoutReader = new InputStreamReader(stdout, StandardCharsets.UTF_8);
          BufferedReader bufferedReader = new BufferedReader(stdoutReader)) {
        XctoolOutputParsing.streamOutputFromReader(bufferedReader, xctoolEventHandler);
      }
    }

    public ImmutableList<TestCaseSummary> getTestCaseSummaries() {
      return xctoolEventHandler.getTestCaseSummaries();
    }
  }

  private static class AppleTestXctestOutputReader
      implements XctestRunTestsStep.OutputReadingCallback {

    private final TestCaseSummariesBuildingXctestEventHandler xctestEventHandler;

    public AppleTestXctestOutputReader(TestRule.TestReportingCallback testReportingCallback) {
      this.xctestEventHandler =
          new TestCaseSummariesBuildingXctestEventHandler(testReportingCallback);
    }

    @Override
    public void readOutput(InputStream output) throws IOException {
      try (InputStreamReader outputReader = new InputStreamReader(output, StandardCharsets.UTF_8);
          BufferedReader outputBufferedReader = new BufferedReader(outputReader)) {
        XctestOutputParsing.streamOutput(outputBufferedReader, xctestEventHandler);
      }
    }

    public ImmutableList<TestCaseSummary> getTestCaseSummaries() {
      return xctestEventHandler.getTestCaseSummaries();
    }
  }

  AppleTest(
      Optional<SourcePath> xctool,
      Optional<Long> xctoolStutterTimeout,
      Tool xctest,
      boolean useXctest,
      String platformName,
      Optional<String> defaultDestinationSpecifier,
      Optional<ImmutableMap<String, String>> destinationSpecifier,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      AppleBundle testBundle,
      Optional<AppleBundle> testHostApp,
      Optional<AppleBundle> uiTestTargetApp,
      ImmutableSet<String> contacts,
      ImmutableSet<String> labels,
      boolean runTestSeparately,
      AppleDeveloperDirectoryForTestsProvider appleDeveloperDirectoryForTestsProvider,
      String testLogDirectoryEnvironmentVariable,
      String testLogLevelEnvironmentVariable,
      String testLogLevel,
      Optional<Long> testRuleTimeoutMs,
      boolean isUiTest,
      Optional<Either<SourcePath, String>> snapshotReferenceImagesPath,
      Optional<ImmutableMap<String, String>> testSpecificEnvironmentVariables) {
    super(buildTarget, projectFilesystem, params);
    this.xctool = xctool;
    this.xctoolStutterTimeout = xctoolStutterTimeout;
    this.useXctest = useXctest;
    this.xctest = xctest;
    this.platformName = platformName;
    this.defaultDestinationSpecifier = defaultDestinationSpecifier;
    this.destinationSpecifier = destinationSpecifier;
    this.testBundle = testBundle;
    this.testHostApp = testHostApp;
    this.uiTestTargetApp = uiTestTargetApp;
    this.contacts = contacts;
    this.labels = labels;
    this.runTestSeparately = runTestSeparately;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.testOutputPath = getPathToTestOutputDirectory().resolve("test-output.json");
    this.testLogsPath = getPathToTestOutputDirectory().resolve("logs");
    this.xctoolStdoutReader = Optional.empty();
    this.xctestOutputReader = Optional.empty();
    this.appleDeveloperDirectoryForTestsProvider = appleDeveloperDirectoryForTestsProvider;
    this.testLogDirectoryEnvironmentVariable = testLogDirectoryEnvironmentVariable;
    this.testLogLevelEnvironmentVariable = testLogLevelEnvironmentVariable;
    this.testLogLevel = testLogLevel;
    this.isUiTest = isUiTest;
    this.snapshotReferenceImagesPath = snapshotReferenceImagesPath;
    this.testSpecificEnvironmentVariables = testSpecificEnvironmentVariables;
  }

  @Override
  public ImmutableSet<String> getLabels() {
    return labels;
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  public Pair<ImmutableList<Step>, ExternalTestRunnerTestSpec> getTestCommand(
      ExecutionContext context,
      TestRunningOptions options,
      BuildContext buildContext,
      TestRule.TestReportingCallback testReportingCallback) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ExternalTestRunnerTestSpec.Builder externalSpec =
        ExternalTestRunnerTestSpec.builder()
            .setTarget(getBuildTarget())
            .setLabels(getLabels())
            .setContacts(getContacts());

    Path resolvedTestBundleDirectory =
        buildContext
            .getSourcePathResolver()
            .getAbsolutePath(Preconditions.checkNotNull(testBundle.getSourcePathToOutput()));

    Path pathToTestOutput = getProjectFilesystem().resolve(getPathToTestOutputDirectory());

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), pathToTestOutput)));

    Path resolvedTestLogsPath = getProjectFilesystem().resolve(testLogsPath);

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                getProjectFilesystem(),
                resolvedTestLogsPath)));

    Path resolvedTestOutputPath = getProjectFilesystem().resolve(testOutputPath);

    Optional<Path> testHostAppPath = extractBundlePathForBundle(testHostApp, buildContext);
    Optional<Path> uiTestTargetAppPath = extractBundlePathForBundle(uiTestTargetApp, buildContext);

    ImmutableMap<String, String> testEnvironmentOverrides =
        ImmutableMap.<String, String>builder()
            .putAll(options.getEnvironmentOverrides())
            .putAll(this.testSpecificEnvironmentVariables.orElse(ImmutableMap.of()))
            .build();

    if (!useXctest) {
      if (!xctool.isPresent()) {
        throw new HumanReadableException(
            "Set xctool_path = /path/to/xctool or xctool_zip_target = //path/to:xctool-zip "
                + "in the [apple] section of .buckconfig to run this test");
      }

      ImmutableSet.Builder<Path> logicTestPathsBuilder = ImmutableSet.builder();
      ImmutableMap.Builder<Path, Path> appTestPathsToHostAppsBuilder = ImmutableMap.builder();
      ImmutableMap.Builder<Path, Map<Path, Path>>
          appTestPathsToTestHostAppPathsToTestTargetAppsBuilder = ImmutableMap.builder();

      if (testHostAppPath.isPresent()) {
        if (uiTestTargetAppPath.isPresent()) {
          appTestPathsToTestHostAppPathsToTestTargetAppsBuilder.put(
              resolvedTestBundleDirectory,
              ImmutableMap.of(testHostAppPath.get(), uiTestTargetAppPath.get()));
        } else {
          appTestPathsToHostAppsBuilder.put(resolvedTestBundleDirectory, testHostAppPath.get());
        }
      } else {
        logicTestPathsBuilder.add(resolvedTestBundleDirectory);
      }

      xctoolStdoutReader = Optional.of(new AppleTestXctoolStdoutReader(testReportingCallback));
      Optional<String> destinationSpecifierArg;
      if (!destinationSpecifier.get().isEmpty()) {
        destinationSpecifierArg =
            Optional.of(
                Joiner.on(',')
                    .join(
                        Iterables.transform(
                            destinationSpecifier.get().entrySet(),
                            input -> input.getKey() + "=" + input.getValue())));
      } else {
        destinationSpecifierArg = defaultDestinationSpecifier;
      }

      Optional<String> snapshotReferenceImagesPath = Optional.empty();
      if (this.snapshotReferenceImagesPath.isPresent()) {
        if (this.snapshotReferenceImagesPath.get().isLeft()) {
          snapshotReferenceImagesPath =
              Optional.of(
                  buildContext
                      .getSourcePathResolver()
                      .getAbsolutePath(this.snapshotReferenceImagesPath.get().getLeft())
                      .toString());
        } else if (this.snapshotReferenceImagesPath.get().isRight()) {
          snapshotReferenceImagesPath =
              Optional.of(
                  getProjectFilesystem()
                      .getPathForRelativePath(this.snapshotReferenceImagesPath.get().getRight())
                      .toString());
        }
      }

      XctoolRunTestsStep xctoolStep =
          new XctoolRunTestsStep(
              getProjectFilesystem(),
              buildContext.getSourcePathResolver().getAbsolutePath(xctool.get()),
              testEnvironmentOverrides,
              xctoolStutterTimeout,
              platformName,
              destinationSpecifierArg,
              logicTestPathsBuilder.build(),
              appTestPathsToHostAppsBuilder.build(),
              appTestPathsToTestHostAppPathsToTestTargetAppsBuilder.build(),
              resolvedTestOutputPath,
              xctoolStdoutReader,
              appleDeveloperDirectoryForTestsProvider,
              options.getTestSelectorList(),
              context.isDebugEnabled(),
              Optional.of(testLogDirectoryEnvironmentVariable),
              Optional.of(resolvedTestLogsPath),
              Optional.of(testLogLevelEnvironmentVariable),
              Optional.of(testLogLevel),
              testRuleTimeoutMs,
              snapshotReferenceImagesPath);
      steps.add(xctoolStep);
      String xctoolTypeSuffix;
      if (uiTestTargetApp.isPresent()) {
        xctoolTypeSuffix = "uitest";
      } else if (testHostApp.isPresent()) {
        xctoolTypeSuffix = "application";
      } else {
        xctoolTypeSuffix = "logic";
      }
      externalSpec.setType("xctool-" + xctoolTypeSuffix);
      externalSpec.setCommand(xctoolStep.getCommand());
      externalSpec.setEnv(xctoolStep.getEnv(context));
    } else {
      xctestOutputReader = Optional.of(new AppleTestXctestOutputReader(testReportingCallback));

      HashMap<String, String> environment = new HashMap<>();
      environment.putAll(xctest.getEnvironment(buildContext.getSourcePathResolver()));
      environment.putAll(testEnvironmentOverrides);
      if (testHostAppPath.isPresent()) {
        environment.put("XCInjectBundleInto", testHostAppPath.get().toString());
      }
      XctestRunTestsStep xctestStep =
          new XctestRunTestsStep(
              getProjectFilesystem(),
              ImmutableMap.copyOf(environment),
              xctest.getCommandPrefix(buildContext.getSourcePathResolver()),
              resolvedTestBundleDirectory,
              resolvedTestOutputPath,
              xctestOutputReader,
              appleDeveloperDirectoryForTestsProvider);
      steps.add(xctestStep);
      externalSpec.setType("xctest");
      externalSpec.setCommand(xctestStep.getCommand());
      externalSpec.setEnv(xctestStep.getEnv(context));
    }

    return new Pair<>(steps.build(), externalSpec.build());
  }

  private Optional<Path> extractBundlePathForBundle(
      Optional<AppleBundle> bundle, BuildContext buildContext) {
    if (!bundle.isPresent()) {
      return Optional.empty();
    }
    Path resolvedBundleDirectory =
        buildContext
            .getSourcePathResolver()
            .getAbsolutePath(Preconditions.checkNotNull(bundle.get().getSourcePathToOutput()));
    return Optional.of(
        resolvedBundleDirectory.resolve(bundle.get().getUnzippedOutputFilePathToBinary()));
  }

  @Override
  public ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      BuildContext buildContext,
      TestReportingCallback testReportingCallback) {
    if (isLegacyUITestConfiguration()) {
      return ImmutableList.of();
    }
    return getTestCommand(executionContext, options, buildContext, testReportingCallback)
        .getFirst();
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      SourcePathResolver pathResolver,
      boolean isUsingTestSelectors) {
    return () -> {
      List<TestCaseSummary> testCaseSummaries;
      if (xctoolStdoutReader.isPresent()) {
        // We've already run the tests with 'xctool' and parsed
        // their output; no need to parse the same output again.
        testCaseSummaries = xctoolStdoutReader.get().getTestCaseSummaries();
      } else if (xctestOutputReader.isPresent()) {
        // We've already run the tests with 'xctest' and parsed
        // their output; no need to parse the same output again.
        testCaseSummaries = xctestOutputReader.get().getTestCaseSummaries();
      } else if (isLegacyUITestConfiguration()) {
        TestCaseSummary noTestsSummary =
            new TestCaseSummary(
                "XCUITest runs not supported with buck test", Collections.emptyList());
        testCaseSummaries = Collections.singletonList(noTestsSummary);
      } else {
        Path resolvedOutputPath = getProjectFilesystem().resolve(testOutputPath);
        try (BufferedReader reader =
            Files.newBufferedReader(resolvedOutputPath, StandardCharsets.UTF_8)) {
          if (useXctest) {
            TestCaseSummariesBuildingXctestEventHandler xctestEventHandler =
                new TestCaseSummariesBuildingXctestEventHandler(NOOP_REPORTING_CALLBACK);
            XctestOutputParsing.streamOutput(reader, xctestEventHandler);
            testCaseSummaries = xctestEventHandler.getTestCaseSummaries();
          } else {
            TestCaseSummariesBuildingXctoolEventHandler xctoolEventHandler =
                new TestCaseSummariesBuildingXctoolEventHandler(NOOP_REPORTING_CALLBACK);
            XctoolOutputParsing.streamOutputFromReader(reader, xctoolEventHandler);
            testCaseSummaries = xctoolEventHandler.getTestCaseSummaries();
          }
        }
      }
      TestResults.Builder testResultsBuilder =
          TestResults.builder()
              .setBuildTarget(getBuildTarget())
              .setTestCases(testCaseSummaries)
              .setContacts(contacts)
              .setLabels(
                  labels.stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()));
      if (getProjectFilesystem().isDirectory(testLogsPath)) {
        for (Path testLogPath : getProjectFilesystem().getDirectoryContents(testLogsPath)) {
          testResultsBuilder.addTestLogPaths(testLogPath);
        }
      }

      return testResultsBuilder.build();
    };
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    // TODO(beng): Refactor the JavaTest implementation; this is identical.
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "__apple_test_%s_output__");
  }

  @Override
  public boolean runTestSeparately() {
    // Tests which run in the simulator must run separately from all other tests;
    // there's a 20 second timeout hard-coded in the iOS Simulator SpringBoard which
    // is hit any time the host is overloaded.
    return runTestSeparately || testHostApp.isPresent() || uiTestTargetApp.isPresent();
  }

  // This test rule just executes the test bundle, so we need it available locally.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return Stream.concat(
        Stream.concat(
                Stream.of(testBundle),
                Stream.concat(Optionals.toStream(testHostApp), Optionals.toStream(uiTestTargetApp)))
            .map(BuildRule::getBuildTarget),
        Optionals.toStream(xctool)
            .map(ruleFinder::filterBuildRuleInputs)
            .flatMap(ImmutableSet::stream)
            .map(BuildRule::getBuildTarget));
  }

  @Override
  public boolean supportsStreamingTests() {
    return true;
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {
    return getTestCommand(
            executionContext, testRunningOptions, buildContext, NOOP_REPORTING_CALLBACK)
        .getSecond();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(getBuildTarget(), testBundle.getSourcePathToOutput());
  }

  public boolean isUiTest() {
    return isUiTest;
  }

  @VisibleForTesting
  public boolean hasTestHost() {
    return testHostApp.isPresent();
  }

  @VisibleForTesting
  public boolean hasUiTestTarget() {
    return uiTestTargetApp.isPresent();
  }

  private boolean isLegacyUITestConfiguration() {
    return isUiTest() && !hasUiTestTarget();
  }
}
