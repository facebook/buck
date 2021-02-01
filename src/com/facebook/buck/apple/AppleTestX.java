/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.test.rule.CoercedTestRunnerSpec;
import com.facebook.buck.core.test.rule.TestXRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Either;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Represents an apple_test rule that implements the test protocol */
public class AppleTestX extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements TestXRule, HasRuntimeDeps {

  private static final String TEST_BUNDLE = "test_bundle";

  private static final String APPLE_CONFIGS_PATH = "apple_configs";

  private final BinaryBuildRule externalBinary;
  private final CoercedTestRunnerSpec specs;
  private final Optional<SourcePath> xctool;

  @AddToRuleKey private final AppleConfigs appleConfigs;

  private final ExplicitBuildTargetSourcePath appleConfigsOutputPaths;

  private final AppleBundle testBundle;

  @AddToRuleKey private final Optional<AppleBundle> testHostApp;

  private final Optional<AppleBundle> uiTestTargetApp;

  private final ImmutableSet<String> contacts;
  private final ImmutableSet<String> labels;

  AppleTestX(
      BinaryBuildRule binary,
      CoercedTestRunnerSpec specs,
      Optional<SourcePath> xctool,
      Optional<Long> stutterTimeout,
      Tool xctest,
      boolean useXctest,
      String platformName,
      Optional<String> defaultDestinationSpecifier,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      AppleBundle testBundle,
      Optional<AppleBundle> testHostApp,
      Optional<AppleBundle> uiTestTargetApp,
      ImmutableSet<String> contacts,
      ImmutableSet<String> labels,
      AppleDeveloperDirectoryForTestsProvider appleDeveloperDirectoryForTestsProvider,
      boolean isUiTest,
      Optional<Either<SourcePath, String>> snapshotReferenceImagesPath,
      Optional<Either<SourcePath, String>> snapshotImagesDiffPath,
      boolean useIdb,
      Path idbPath) {
    super(buildTarget, projectFilesystem, params);

    this.appleConfigs =
        new AppleConfigs(
            testHostApp,
            uiTestTargetApp,
            xctool,
            stutterTimeout,
            useXctest,
            xctest,
            platformName,
            defaultDestinationSpecifier,
            appleDeveloperDirectoryForTestsProvider,
            isUiTest,
            snapshotReferenceImagesPath,
            snapshotImagesDiffPath,
            useIdb,
            idbPath);

    this.externalBinary = binary;
    this.specs = specs;
    this.xctool = xctool;
    this.testBundle = testBundle;
    this.testHostApp = testHostApp;
    this.uiTestTargetApp = uiTestTargetApp;
    this.contacts = contacts;
    this.labels = labels;
    this.appleConfigsOutputPaths =
        ExplicitBuildTargetSourcePath.of(
            buildTarget,
            BuildPaths.getGenDir(projectFilesystem, buildTarget).resolve(APPLE_CONFIGS_PATH));
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
  public CoercedTestRunnerSpec getSpecs() {
    return specs;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    // TODO(beng): Refactor the JavaTest implementation; this is identical.
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "__apple_test_%s_output__");
  }

  // This test rule just executes the test bundle, so we need it available locally.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return Stream.concat(
        Stream.of(externalBinary.getBuildTarget()),
        Stream.concat(
            Stream.concat(
                    Stream.of(testBundle),
                    Stream.concat(RichStream.from(testHostApp), RichStream.from(uiTestTargetApp)))
                .map(BuildRule::getBuildTarget),
            RichStream.from(xctool)
                .map(buildRuleResolver::filterBuildRuleInputs)
                .flatMap(ImmutableSet::stream)
                .map(BuildRule::getBuildTarget)));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepBuilder = ImmutableList.builder();
    stepBuilder.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                getProjectFilesystem(),
                Objects.requireNonNull(appleConfigsOutputPaths.getResolvedPath().getParent()))),
        new AbstractExecutionStep("write apple_configs to file") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {

            getProjectFilesystem()
                .writeContentsToPath(
                    appleConfigs.asJson(
                        buildContext.getSourcePathResolver(), getProjectFilesystem()),
                    appleConfigsOutputPaths.getResolvedPath());
            return StepExecutionResults.SUCCESS;
          }
        });
    return stepBuilder.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(
        getBuildTarget(), Objects.requireNonNull(externalBinary.getSourcePathToOutput()));
  }

  /**
   * not cacheable because our build steps writes out a bunch of apple configurations for the test
   * specific to the run time.
   */
  @Override
  public boolean isCacheable() {
    return false;
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToSupplementaryOutput(String name) {
    if (name.equals(TEST_BINARY_OUTPUT)) {
      return getSourcePathToOutput();
    }
    if (name.equals(TEST_BUNDLE)) {
      return testBundle.getSourcePathToOutput();
    }

    if (name.equals(APPLE_CONFIGS_PATH)) {
      return appleConfigsOutputPaths;
    }

    return null;
  }

  /** The apple configs and toolchains that is specified to Buck for test execution */
  private static class AppleConfigs implements AddsToRuleKey {

    public static final String USE_XCTEST = "use_xctest";
    public static final String USE_IDB = "use_idb";
    public static final String IS_UI_TEST = "is_ui_test";
    public static final String XCTOOL_PATH = "xctool_path";
    public static final String XCTEST_CMD = "xctest_cmd";
    public static final String XCTEST_ENV = "xctest_env";
    public static final String IDB_PATH = "idb_path";
    public static final String STUTTER_TIMEOUT = "stutter_timeout";
    public static final String PLATFORM = "platform";
    public static final String DEFAULT_DESTINATION = "default_destination";
    public static final String DEVELOPER_DIRECTORY_FOR_TESTS = "developer_directory_for_tests";
    public static final String SNAPSHOT_REFERENCE_IMG_PATH = "snapshot_reference_img_path";
    public static final String SNAPSHOT_IMAGES_DIFF_PATH = "snapshot_images_diff_path";

    private static final String UI_TEST_TARGET_APP = "ui_test_target_app";
    private static final String TEST_HOST_APP = "test_host_app";

    private final Optional<AppleBundle> testHostApp;
    private final Optional<AppleBundle> uiTestTargetApp;
    private final Optional<SourcePath> xctool;
    private final Optional<Long> stutterTimeout;
    private final boolean useXctest;
    private final Tool xctest;
    private final String platformName;
    private final Optional<String> defaultDestinationSpecifier;

    /**
     * Absolute path to xcode developer dir.
     *
     * <p>Should not be added to rule key.
     */
    private final AppleDeveloperDirectoryForTestsProvider appleDeveloperDirectoryForTestsProvider;

    @AddToRuleKey private final boolean isUiTest;
    private final Optional<Either<SourcePath, String>> snapshotReferenceImagesPath;
    private final Optional<Either<SourcePath, String>> snapshotImagesDiffPath;
    private final boolean useIdb;
    private final Path idbPath;

    private AppleConfigs(
        Optional<AppleBundle> testHostApp,
        Optional<AppleBundle> uiTestTargetApp,
        Optional<SourcePath> xctool,
        Optional<Long> stutterTimeout,
        boolean useXctest,
        Tool xctest,
        String platformName,
        Optional<String> defaultDestinationSpecifier,
        AppleDeveloperDirectoryForTestsProvider appleDeveloperDirectoryForTestsProvider,
        boolean isUiTest,
        Optional<Either<SourcePath, String>> snapshotReferenceImagesPath,
        Optional<Either<SourcePath, String>> snapshotImagesDiffPath,
        boolean useIdb,
        Path idbPath) {
      this.testHostApp = testHostApp;
      this.uiTestTargetApp = uiTestTargetApp;
      this.xctool = xctool;
      this.stutterTimeout = stutterTimeout;
      this.useXctest = useXctest;
      this.xctest = xctest;
      this.platformName = platformName;
      this.defaultDestinationSpecifier = defaultDestinationSpecifier;
      this.appleDeveloperDirectoryForTestsProvider = appleDeveloperDirectoryForTestsProvider;
      this.isUiTest = isUiTest;
      this.snapshotReferenceImagesPath = snapshotReferenceImagesPath;
      this.snapshotImagesDiffPath = snapshotImagesDiffPath;
      this.useIdb = useIdb;
      this.idbPath = idbPath;
    }

    private String asJson(
        SourcePathResolverAdapter sourcePathResolver, ProjectFilesystem filesystem)
        throws IOException {
      ByteArrayOutputStream jsonStream = new ByteArrayOutputStream();
      try (JsonGenerator generator = ObjectMappers.createGenerator(jsonStream)) {
        generator.writeStartObject();
        generator.writeBooleanField(USE_XCTEST, useXctest);
        generator.writeBooleanField(USE_IDB, useIdb);
        generator.writeBooleanField(IS_UI_TEST, isUiTest);

        generator.writeObjectField(
            XCTOOL_PATH,
            xctool.map(sourcePath -> sourcePathResolver.getAbsolutePath(sourcePath).toString()));
        generator.writeObjectField(XCTEST_CMD, xctest.getCommandPrefix(sourcePathResolver));
        generator.writeObjectField(XCTEST_ENV, xctest.getEnvironment(sourcePathResolver));
        generator.writeStringField(IDB_PATH, idbPath.toString());

        generator.writeObjectField(STUTTER_TIMEOUT, stutterTimeout);

        generator.writeStringField(PLATFORM, platformName);
        generator.writeStringField(DEFAULT_DESTINATION, defaultDestinationSpecifier.orElse(""));
        generator.writeStringField(
            DEVELOPER_DIRECTORY_FOR_TESTS,
            appleDeveloperDirectoryForTestsProvider
                .getAppleDeveloperDirectoryForTests()
                .toString());
        generator.writeStringField(
            SNAPSHOT_REFERENCE_IMG_PATH,
            snapshotReferenceImagesPath
                .map(
                    pathOrStr -> {
                      if (pathOrStr.isLeft()) {
                        return sourcePathResolver.getAbsolutePath(pathOrStr.getLeft()).toString();
                      }

                      return filesystem.getPathForRelativePath(pathOrStr.getRight()).toString();
                    })
                .orElse(""));

        generator.writeStringField(
          SNAPSHOT_IMAGES_DIFF_PATH,
          snapshotImagesDiffPath
            .map(
              pathOrStr -> {
                if (pathOrStr.isLeft()) {
                  return sourcePathResolver.getAbsolutePath(pathOrStr.getLeft()).toString();
                }

                return filesystem.getPathForRelativePath(pathOrStr.getRight()).toString();
              })
            .orElse(""));

        generator.writeObjectField(
            UI_TEST_TARGET_APP,
            AppleTest.extractBundlePathForBundle(uiTestTargetApp, sourcePathResolver)
                .map(Path::toString));

        generator.writeObjectField(
            TEST_HOST_APP,
            AppleTest.extractBundlePathForBundle(testHostApp, sourcePathResolver)
                .map(Path::toString));
        generator.writeEndObject();
      }
      return jsonStream.toString();
    }
  }
}
