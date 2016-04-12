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

import static com.facebook.buck.cxx.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleBinaryIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testAppleBinaryBuildsBinary() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(BuildTargets.getGenPath(target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(Paths.get(outputPath.toString() + "-LinkMap.txt")), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryUsesPlatformLinkerFlags() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestAppWithNonstandardMain");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(BuildTargets.getGenPath(target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }


  @Test
  public void testAppleBinaryAppBuildsAppWithDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#app");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget = target.withFlavors(
        AppleBinaryDescription.APP_FLAVOR,
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR,
        AppleDebugFormat.DWARF_AND_DSYM.getFlavor());
    Path outputPath = workspace.getPath(
        BuildTargets.getGenPath(appTarget, "%s")
            .resolve(appTarget.getShortName() + ".app"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(outputPath.resolve("Info.plist")), is(true));

    Path dsymPath = workspace.getPath(
        BuildTargets.getGenPath(appTarget, "%s")
            .resolve(appTarget.getShortName() + ".app.dSYM"));
    assertThat(Files.exists(dsymPath), is(true));
    assertThat(
        workspace.runCommand(
            "file",
            outputPath.resolve(appTarget.getShortName()).toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryAppBuildsAppWithoutDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#app,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget = target.withFlavors(
        AppleBinaryDescription.APP_FLAVOR,
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR,
        AppleDebugFormat.NONE.getFlavor());
    Path outputPath = workspace.getPath(
        BuildTargets.getGenPath(appTarget, "%s")
            .resolve(appTarget.getShortName() + ".app"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(outputPath.resolve("Info.plist")), is(true));

    Path dsymPath = workspace.getPath(
        BuildTargets.getGenPath(appTarget, "%s")
            .resolve(appTarget.getShortName() + ".app.dSYM"));
    assertThat(Files.exists(dsymPath), is(false));
  }

  @Test
  public void testAppleBinaryWithSystemFrameworksBuildsSomething() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_system_frameworks_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(BuildTargets.getGenPath(target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryWithLibraryDependencyBuildsSomething() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_library_dependency_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(BuildTargets.getGenPath(target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryWithLibraryDependencyWithSystemFrameworksBuildsSomething()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_library_dependency_with_system_frameworks_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(BuildTargets.getGenPath(target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleLibraryPropagatesExportedPlatformLinkerFlags()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_library_dependency_builds_something", tmp);
    workspace.setUp();
    ProjectWorkspace.ProcessResult buildResult =
      workspace.runBuckCommand("build", "//Apps/TestApp:BadTestApp");
    buildResult.assertFailure();
    String stderr = buildResult.getStderr();
    assertTrue(stderr.contains("bad-flag"));
  }

  @Test
  public void testAppleBinaryHeaderSymlinkTree() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_header_symlink_tree", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#default," +
            CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = tmp.getRoot().toRealPath();

    Path inputPath = projectRoot.resolve(
        buildTarget.getBasePath());
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(buildTarget, "%s"));

    assertIsSymbolicLink(
        outputPath.resolve("Header.h"),
        inputPath.resolve("Header.h"));
    assertIsSymbolicLink(
        outputPath.resolve("TestApp/Header.h"),
        inputPath.resolve("Header.h"));
  }

  @Test
  public void testAppleBinaryWithHeaderMaps() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_header_maps", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = workspace.getPath(BuildTargets.getGenPath(target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleXcodeError() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    String expectedError =
        "Apps/TestApp/main.c:2:3: error: use of undeclared identifier 'SomeType'\n" +
        "  SomeType a;\n" +
        "  ^\n";
    String expectedWarning =
        "Apps/TestApp/main.c:3:10: warning: implicit conversion from 'double' to 'int' changes " +
        "value from 0.42 to 0 [-Wliteral-conversion]\n" +
        "  return 0.42;\n" +
        "  ~~~~~~ ^~~~\n";
    String expectedSummary = "1 warning and 1 error generated.\n";

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_xcode_error", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", "//Apps/TestApp:TestApp");
    buildResult.assertFailure();
    String stderr = buildResult.getStderr();

    assertTrue(
        stderr.contains(expectedError) &&
        stderr.contains(expectedWarning) &&
        stderr.contains(expectedSummary));
  }

  @Test
  public void testAppleBinaryIsHermetic() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_is_hermetic", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#iphonesimulator-x86_64");
    ProjectWorkspace.ProcessResult first = workspace.runBuckCommand(
        workspace.getPath("first"),
        "build",
        target.getFullyQualifiedName());
    first.assertSuccess();

    ProjectWorkspace.ProcessResult second = workspace.runBuckCommand(
        workspace.getPath("second"),
        "build",
        target.getFullyQualifiedName());
    second.assertSuccess();

    Path outputPath = BuildTargets.getGenPath(
        target.withFlavors(
            ImmutableFlavor.of("iphonesimulator-x86_64"),
            ImmutableFlavor.of("compile-" + sanitize("TestClass.m.o"))),
        "%s/TestClass.m.o");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(Paths.get("first").resolve(outputPath)),
        workspace.getPath(Paths.get("second").resolve(outputPath)));
    outputPath = BuildTargets.getGenPath(target, "%s");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(Paths.get("first").resolve(outputPath)),
        workspace.getPath(Paths.get("second").resolve(outputPath)));
  }

  @Test
  public void testAppleBinaryBuildsFatBinaries() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance(
        "//:DemoAppBinary#iphonesimulator-i386,iphonesimulator-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path output = workspace.getPath(BuildTargets.getGenPath(target, "%s"));
    assertThat(Files.exists(output), is(true));
    assertThat(
        workspace.runCommand("file", output.toString()).getStdout().get(),
        containsString("executable"));
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand("lipo", output.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(
        lipoVerifyResult.getStderr().or(""),
        0,
        lipoVerifyResult.getExitCode());
  }

  @Test
  public void testAppleBinaryBuildsFatBinariesWithDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance(
        "//:DemoAppBinary#iphonesimulator-i386,iphonesimulator-x86_64");
    BuildTarget targetToBuild = target
        .withAppendedFlavor(AppleDebugFormat.DWARF_AND_DSYM.getFlavor());
    BuildTarget dsymTarget = target.withAppendedFlavor(AppleDsym.RULE_FLAVOR);
    workspace.runBuckCommand("build", targetToBuild.getFullyQualifiedName()).assertSuccess();
    Path output = workspace.getPath(AppleDsym.getDsymOutputPath(dsymTarget));
    AppleDsymTestUtil
        .checkDsymFileHasDebugSymbolsForMainForConcreteArchitectures(
            workspace,
            output,
            Optional.of(ImmutableList.of("i386", "x86_64")));
  }

  @Test
  public void testFlavoredAppleBundleBuildsAndDsymFileCreated() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#dwarf-and-dsym");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format=none",
        target.getFullyQualifiedName())
        .assertSuccess();
    BuildTarget appTarget = target.withFlavors(
        AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path output = workspace.getPath(
        BuildTargets.getGenPath(appTarget, "%s")
            .resolve(target.getShortName() + ".app.dSYM")
            .resolve("Contents/Resources/DWARF")
            .resolve(target.getShortName()));
    assertThat(Files.exists(output), Matchers.equalTo(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForMain(workspace, output);
  }

  @Test
  public void testFlavoredAppleBundleBuildsAndDsymFileIsNotCreated() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format=dwarf_and_dsym",
        target.getFullyQualifiedName())
        .assertSuccess();
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargets.getGenPath(target, "%s")
                    .resolve(target.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(target.getShortName()))),
        Matchers.equalTo(false));
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargets
                    .getGenPath(
                        target.withFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor()),
                        "%s")
                    .resolve(target.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(target.getShortName()))),
        Matchers.equalTo(false));
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargets.getGenPath(target.withFlavors(), "%s")
                    .resolve(target.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(target.getShortName()))),
        Matchers.equalTo(false));
  }

  @Test
  public void testAppleBundleDebugFormatRespectsDefaultConfigSettingDSYM() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp");
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format=dwarf_and_dsym",
        target.getFullyQualifiedName())
        .assertSuccess();
    BuildTarget appTarget = target.withFlavors(
        AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path dwarfPath = workspace.getPath(
        BuildTargets.getGenPath(appTarget, "%s")
            .resolve(appTarget.getShortName() + ".app.dSYM")
            .resolve("Contents/Resources/DWARF")
            .resolve(appTarget.getShortName()));
    assertThat(Files.exists(dwarfPath), Matchers.equalTo(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForMain(workspace, dwarfPath);
  }

  @Test
  public void testAppleBundleDebugFormatRespectsDefaultConfigSettingNoDebug() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp");
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format=none",
        target.getFullyQualifiedName())
        .assertSuccess();
    BuildTarget appTarget = target.withFlavors(AppleDebugFormat.NONE.getFlavor());
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargets.getGenPath(appTarget, "%s")
                    .resolve(appTarget.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(appTarget.getShortName()))),
        Matchers.equalTo(false));
  }

  private static void assertIsSymbolicLink(
      Path link,
      Path target) throws IOException {
    assertTrue(Files.isSymbolicLink(link));
    assertEquals(
        target,
        Files.readSymbolicLink(link));
  }
}
