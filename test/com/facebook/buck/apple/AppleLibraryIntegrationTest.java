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

import static com.facebook.buck.cxx.toolchain.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class AppleLibraryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testAppleLibraryBuildsSomething() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#static,default");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void appleLibraryUsesPlatformDepOfSpecifiedPlatform() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_platform_deps", tmp);
    workspace.setUp();

    // arm64 platform dependency works, so the build should succeed
    workspace
        .runBuckCommand(
            "build",
            BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#iphoneos-arm64")
                .getFullyQualifiedName())
        .assertSuccess();

    // armv7 platform dependency is broken, so the build should fail
    workspace
        .runBuckCommand(
            "build",
            BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#iphoneos-armv7")
                .getFullyQualifiedName())
        .assertFailure();
  }

  @Test
  public void testAppleLibraryWithHeaderPathPrefix() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_header_path_prefix", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#static,default");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testCanUseAHeaderWithoutPrefix() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_header_path_prefix", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary2:TestLibrary2#static,default");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryWithDefaultsInConfigBuildsSomething() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    workspace.addBuckConfigLocalOption(
        "defaults.apple_library", "platform", "iphonesimulator-x86_64");
    workspace.addBuckConfigLocalOption("defaults.apple_library", "type", "shared");

    BuildTarget target = BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    BuildTarget implicitTarget =
        target.withAppendedFlavors(
            InternalFlavor.of("shared"), InternalFlavor.of("iphonesimulator-x86_64"));
    assertTrue(
        Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, implicitTarget, "%s"))));
  }

  @Test
  public void testAppleLibraryWithDefaultsInRuleBuildsSomething() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_platform_and_type", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    BuildTarget implicitTarget =
        target.withAppendedFlavors(
            InternalFlavor.of("shared"), InternalFlavor.of("iphoneos-arm64"));
    Path outputPath = workspace.getPath(BuildTargets.getGenPath(filesystem, implicitTarget, "%s"));
    assertTrue(Files.exists(outputPath));
  }

  @Test
  public void testAppleLibraryBuildsForWatchOS() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#watchos-armv7k,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryBuildsForWatchSimulator() throws IOException {
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHSIMULATOR));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#watchsimulator-i386,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryBuildsForAppleTVOS() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.APPLETVOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#appletvos-arm64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryBuildsForAppleTVSimulator() throws IOException {
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.APPLETVSIMULATOR));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#appletvsimulator-x86_64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryBuildsSomethingUsingAppleCxxPlatform() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#static,macosx-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryHeaderSymlinkTree() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_header_symlink_tree", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#"
                + "default,"
                + CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
    ProcessResult result = workspace.runBuckCommand("build", buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path inputPath = workspace.getPath(buildTarget.getBasePath()).toRealPath();
    Path outputPath =
        workspace.getPath(BuildTargets.getGenPath(filesystem, buildTarget, "%s")).toRealPath();

    assertIsSymbolicLink(
        outputPath.resolve("PrivateHeader.h"), inputPath.resolve("PrivateHeader.h"));
    assertIsSymbolicLink(
        outputPath.resolve("TestLibrary/PrivateHeader.h"), inputPath.resolve("PrivateHeader.h"));
    assertIsSymbolicLink(outputPath.resolve("PublicHeader.h"), inputPath.resolve("PublicHeader.h"));
  }

  @Test
  public void testAppleLibraryBuildsFramework() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64,no-debug");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    assertThat(Files.exists(frameworkPath.resolve("Resources/Info.plist")), is(true));
    Path libraryPath = frameworkPath.resolve("TestLibrary");
    assertThat(Files.exists(libraryPath), is(true));
    assertThat(
        workspace.runCommand("file", libraryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleLibraryBuildsFrameworkIOS() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.IPHONESIMULATOR));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#framework,iphonesimulator-x86_64,no-debug");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    assertThat(Files.exists(frameworkPath.resolve("Info.plist")), is(true));
    Path libraryPath = frameworkPath.resolve("TestLibrary");
    assertThat(Files.exists(libraryPath), is(true));
    assertThat(
        workspace.runCommand("file", libraryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void appleLibraryBuildsMultiarchFramework() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
                "//Libraries/TestLibrary:TestLibrary#macosx-x86_64,macosx-i386")
            .withAppendedFlavors(
                AppleDescriptions.FRAMEWORK_FLAVOR, AppleDebugFormat.NONE.getFlavor());
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("TestLibrary.framework"));
    Path libraryPath = frameworkPath.resolve("TestLibrary");
    assertThat(Files.exists(libraryPath), is(true));
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand("lipo", libraryPath.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(lipoVerifyResult.getStderr().orElse(""), 0, lipoVerifyResult.getExitCode());
    assertThat(
        workspace.runCommand("file", libraryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleFrameworkWithDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    ProcessResult result =
        workspace.runBuckCommand(
            "build",
            "//Libraries/TestLibrary:TestLibrary#dwarf-and-dsym,framework,macosx-x86_64",
            "--config",
            "cxx.cflags=-g");
    result.assertSuccess();

    Path dsymPath =
        tmp.getRoot()
            .resolve(filesystem.getBuckPaths().getGenDir())
            .resolve(
                "Libraries/TestLibrary/"
                    + "TestLibrary#dwarf-and-dsym,framework,include-frameworks,macosx-x86_64/"
                    + "TestLibrary.framework.dSYM");
    assertThat(Files.exists(dsymPath), is(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbol("+[TestClass answer]", workspace, dsymPath);
  }

  @Test
  public void testAppleDynamicLibraryProducesDylib() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_library_shared", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary")
            .withAppendedFlavors(
                InternalFlavor.of("macosx-x86_64"), CxxDescriptionEnhancer.SHARED_FLAVOR);

    ProcessResult result =
        workspace.runBuckCommand(
            "build", target.getFullyQualifiedName(), "--config", "cxx.cflags=-g");
    result.assertSuccess();

    Path outputPath = workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
  }

  @Test
  public void testAppleDynamicLibraryWithDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_library_shared", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary")
            .withAppendedFlavors(
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
                InternalFlavor.of("macosx-x86_64"));

    ProcessResult result =
        workspace.runBuckCommand(
            "build", target.getFullyQualifiedName(), "--config", "cxx.cflags=-g");
    result.assertSuccess();

    BuildTarget implicitTarget =
        target.withAppendedFlavors(CxxStrip.RULE_FLAVOR, StripStyle.NON_GLOBAL_SYMBOLS.getFlavor());
    Path outputPath = workspace.getPath(BuildTargets.getGenPath(filesystem, implicitTarget, "%s"));
    assertThat(Files.exists(outputPath), is(true));

    Path dsymPath =
        tmp.getRoot()
            .resolve(filesystem.getBuckPaths().getGenDir())
            .resolve("Libraries/TestLibrary")
            .resolve("TestLibrary#apple-dsym,macosx-x86_64,shared.dSYM");
    assertThat(Files.exists(dsymPath), is(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbol("+[TestClass answer]", workspace, dsymPath);
  }

  @Test
  public void frameworkContainsFrameworkDependencies() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        AppleDebugFormat.DWARF.getFlavor(),
                        AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    Path frameworksPath = frameworkPath.resolve("Frameworks");
    assertThat(Files.exists(frameworksPath), is(true));
    Path depPath = frameworksPath.resolve("TestLibraryDep.framework/TestLibraryDep");
    assertThat(Files.exists(depPath), is(true));
    assertThat(
        workspace.runCommand("file", depPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
    Path transitiveDepPath =
        frameworksPath.resolve("TestLibraryTransitiveDep.framework/TestLibraryTransitiveDep");
    assertThat(Files.exists(transitiveDepPath), is(true));
    assertThat(
        workspace.runCommand("file", transitiveDepPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void frameworkDependenciesDoNotContainTransitiveDependencies() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        AppleDebugFormat.DWARF.getFlavor(),
                        AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    Path frameworksPath = frameworkPath.resolve("Frameworks");
    assertThat(Files.exists(frameworksPath), is(true));
    Path depFrameworksPath = frameworksPath.resolve("TestLibraryDep.framework/Frameworks");
    assertThat(Files.exists(depFrameworksPath), is(false));
  }

  @Test
  public void noIncludeFrameworksDoesntContainFrameworkDependencies() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#"
                + "dwarf-and-dsym,framework,macosx-x86_64,no-include-frameworks");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, target, "%s").resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    assertThat(Files.exists(frameworkPath.resolve("Resources/Info.plist")), is(true));
    Path libraryPath = frameworkPath.resolve("TestLibrary");
    assertThat(Files.exists(libraryPath), is(true));
    assertThat(
        workspace.runCommand("file", libraryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
    Path frameworksPath = frameworkPath.resolve("Contents/Frameworks");
    assertThat(Files.exists(frameworksPath), is(false));
  }

  @Test
  public void testAppleLibraryExportedHeaderSymlinkTree() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_header_symlink_tree", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary")
            .withAppendedFlavors(
                CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
                HeaderMode.SYMLINK_TREE_ONLY.getFlavor());
    ProcessResult result = workspace.runBuckCommand("build", buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path inputPath = workspace.getPath(buildTarget.getBasePath()).toRealPath();
    Path outputPath =
        workspace.getPath(BuildTargets.getGenPath(filesystem, buildTarget, "%s")).toRealPath();

    assertIsSymbolicLink(
        outputPath.resolve("TestLibrary/PublicHeader.h"), inputPath.resolve("PublicHeader.h"));
  }

  @Test
  public void testAppleLibraryIsHermetic() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_library_is_hermetic", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#static,iphonesimulator-x86_64");
    ProcessResult first =
        workspace.runBuckCommand(
            workspace.getPath("first"), "build", target.getFullyQualifiedName());
    first.assertSuccess();

    ProcessResult second =
        workspace.runBuckCommand(
            workspace.getPath("second"), "build", target.getFullyQualifiedName());
    second.assertSuccess();

    Path objectPath =
        BuildTargets.getGenPath(
                filesystem,
                target.withFlavors(
                    InternalFlavor.of("compile-" + sanitize("TestClass.m.o")),
                    InternalFlavor.of("iphonesimulator-x86_64")),
                "%s__")
            .resolve("TestClass.m.o");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(Paths.get("first").resolve(objectPath)),
        workspace.getPath(Paths.get("second").resolve(objectPath)));
    Path libraryPath =
        BuildTargets.getGenPath(filesystem, target, "%s").resolve("libTestLibrary.a");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(Paths.get("first").resolve(libraryPath)),
        workspace.getPath(Paths.get("second").resolve(libraryPath)));
  }

  @Test
  public void testBuildEmptySourceAppleLibrary() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_source_targets", tmp);
    workspace.setUp();
    BuildTarget target =
        workspace
            .newBuildTarget("//:real-none#iphonesimulator-x86_64")
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    Path binaryOutput =
        workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s/libreal-none.dylib"));
    assertThat(Files.exists(binaryOutput), is(true));
  }

  @Test
  public void testBuildUsingPrefixHeaderFromCxxPrecompiledHeader() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "precompiled_header", tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:library#iphonesimulator-x86_64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }

  @Test
  public void testBuildUsingPrecompiledHeaderInOtherCell() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "multicell_precompiled_header", tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:library#iphonesimulator-x86_64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }

  @Test
  public void testBuildAppleLibraryThatHasSwift() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_source_targets", tmp);
    workspace.setUp();
    BuildTarget target =
        workspace
            .newBuildTarget("//:none-swift#iphonesimulator-x86_64")
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    Path binaryOutput =
        workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s/libnone-swift.dylib"));
    assertThat(Files.exists(binaryOutput), is(true));

    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        containsString("libswiftCore.dylib"));
  }

  @Test
  public void testBuildAppleLibraryWhereObjcUsesObjcDefinedInSwiftViaBridgingHeader()
      throws Exception {
    testDylibSwiftScenario(
        "apple_library_objc_uses_objc_from_swift_via_bridging_diff_lib", "Bar", "Foo");
  }

  @Test
  public void testBuildAppleLibraryWhereObjcUsesSwiftAcrossDifferentLibraries() throws Exception {
    testDylibSwiftScenario("apple_library_objc_uses_swift_diff_lib", "Bar", "Foo");
  }

  @Test
  public void testBuildAppleLibraryWhereSwiftUsesObjCAcrossDifferentLibraries() throws Exception {
    testDylibSwiftScenario("apple_library_swift_uses_objc_diff_lib", "Bar");
  }

  @Test
  public void testBuildAppleLibraryWhereSwiftUsesSwiftAcrossDifferentLibraries() throws Exception {
    testDylibSwiftScenario("apple_library_swift_uses_swift_diff_lib", "Bar");
  }

  @Test
  public void testBuildAppleLibraryWhereObjCUsesSwiftWithinSameLib() throws Exception {
    testDylibSwiftScenario("apple_library_objc_uses_swift_same_lib", "Mixed");
  }

  @Test
  public void testBuildAppleLibraryWhereSwiftUsesObjCWithinSameLib() throws Exception {
    testDylibSwiftScenario("apple_library_swift_uses_objc_same_lib", "Mixed");
  }

  @Test
  public void testBuildAppleLibraryWhereSwiftDefinedUsingExportFile() throws Exception {
    testDylibSwiftScenario("apple_library_swift_using_export_file", "Mixed");
  }

  public void testDylibSwiftScenario(String scenario, String targetName) throws Exception {
    testDylibSwiftScenario(scenario, targetName, targetName);
  }

  public void testDylibSwiftScenario(
      String scenario, String dylibTargetName, String swiftRuntimeDylibTargetName)
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");
    BuildTarget dylibTarget =
        workspace
            .newBuildTarget(String.format("//:%s#macosx-x86_64", dylibTargetName))
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    ProcessResult result = workspace.runBuckCommand("build", dylibTarget.getFullyQualifiedName());
    result.assertSuccess();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    String dylibPathFormat = "%s/" + String.format("lib%s.dylib", dylibTargetName);
    Path binaryOutput =
        workspace.getPath(BuildTargets.getGenPath(filesystem, dylibTarget, dylibPathFormat));
    assertThat(Files.exists(binaryOutput), is(true));

    BuildTarget swiftRuntimeTarget =
        workspace
            .newBuildTarget(String.format("//:%s#macosx-x86_64", swiftRuntimeDylibTargetName))
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    String swiftRuntimePathFormat =
        "%s/" + String.format("lib%s.dylib", swiftRuntimeDylibTargetName);
    Path swiftRuntimeBinaryOutput =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, swiftRuntimeTarget, swiftRuntimePathFormat));
    assertThat(
        workspace.runCommand("otool", "-L", swiftRuntimeBinaryOutput.toString()).getStdout().get(),
        containsString("libswiftCore.dylib"));
  }

  @Test
  public void testModulewrap() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_swift_uses_objc_same_lib", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");
    workspace.addBuckConfigLocalOption("swift", "use_modulewrap", "true");
    BuildTarget dylibTarget =
        workspace
            .newBuildTarget("//:Mixed#dwarf-and-dsym,macosx-x86_64")
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    ProcessResult result = workspace.runBuckCommand("build", dylibTarget.getFullyQualifiedName());
    result.assertSuccess();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    Path dwarfPath =
        tmp.getRoot()
            .resolve(filesystem.getBuckPaths().getGenDir())
            .resolve("Mixed#apple-dsym,macosx-x86_64,shared.dSYM")
            .resolve("Contents/Resources/DWARF/Mixed");
    assertThat(Files.exists(dwarfPath), is(true));
    AppleDsymTestUtil.checkDsymFileHasSection("__SWIFT", "__ast", workspace, dwarfPath);
  }

  @Test
  public void testBuildAppleLibraryUsingBridingHeaderAndSwiftDotH() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "import_current_module_via_bridging_header", tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:Greeter");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }

  private static void assertIsSymbolicLink(Path link, Path target) throws IOException {
    assertTrue(Files.isSymbolicLink(link));
    assertEquals(target, Files.readSymbolicLink(link));
  }
}
