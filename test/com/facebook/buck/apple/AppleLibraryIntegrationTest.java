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
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleLibraryIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testAppleLibraryBuildsSomething() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#static,default");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryWithDefaultsInConfigBuildsSomething() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());
    workspace.writeContentsToPath(
        "[defaults.apple_library]\n  platform = iphonesimulator-x86_64\n  type = shared\n",
        ".buckconfig.local");

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    BuildTarget implicitTarget = target.withAppendedFlavors(
        ImmutableFlavor.of("shared"),
        ImmutableFlavor.of("iphonesimulator-x86_64")
    );
    assertTrue(
        Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, implicitTarget, "%s"))));
  }

  @Test
  public void testAppleLibraryWithDefaultsInRuleBuildsSomething() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_with_platform_and_type", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    BuildTarget implicitTarget = target.withAppendedFlavors(
        ImmutableFlavor.of("shared"),
        ImmutableFlavor.of("iphoneos-arm64")
    );
    Path outputPath = workspace.getPath(BuildTargets.getGenPath(filesystem, implicitTarget, "%s"));
    assertTrue(Files.exists(outputPath));
  }
  @Test
  public void testAppleLibraryBuildsForWatchOS() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#watchos-armv7k,static");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryBuildsForWatchSimulator() throws IOException {
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHSIMULATOR));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#watchsimulator-i386,static");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryBuildsForAppleTVOS() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.APPLETVOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#appletvos-arm64,static");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryBuildsForAppleTVSimulator() throws IOException {
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.APPLETVSIMULATOR));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#appletvsimulator-x86_64,static");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryBuildsSomethingUsingAppleCxxPlatform() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#static,macosx-x86_64");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testAppleLibraryHeaderSymlinkTree() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_header_symlink_tree", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#" +
            "default," + CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path inputPath = workspace.getPath(buildTarget.getBasePath()).toRealPath();
    Path outputPath =
        workspace.getPath(BuildTargets.getGenPath(filesystem, buildTarget, "%s")).toRealPath();

    assertIsSymbolicLink(
        outputPath.resolve("PrivateHeader.h"),
        inputPath.resolve("PrivateHeader.h"));
    assertIsSymbolicLink(
        outputPath.resolve("TestLibrary/PrivateHeader.h"),
        inputPath.resolve("PrivateHeader.h"));
    assertIsSymbolicLink(
        outputPath.resolve("PublicHeader.h"),
        inputPath.resolve("PublicHeader.h"));
  }

  @Test
  public void testAppleLibraryBuildsFramework() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64,no-debug");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
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

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#framework,iphonesimulator-x86_64,no-debug");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
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

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64,macosx-i386,no-debug");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve("TestLibrary.framework"));
    Path libraryPath = frameworkPath.resolve("TestLibrary");
    assertThat(Files.exists(libraryPath), is(true));
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand("lipo", libraryPath.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(
        lipoVerifyResult.getStderr().or(""),
        0,
        lipoVerifyResult.getExitCode());
    assertThat(
        workspace.runCommand("file", libraryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleFrameworkWithDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#dwarf-and-dsym,framework,macosx-x86_64",
        "--config",
        "cxx.cflags=-g");
    result.assertSuccess();

    Path dsymPath = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("Libraries/TestLibrary/" +
            "TestLibrary#dwarf-and-dsym,framework,include-frameworks,macosx-x86_64/" +
            "TestLibrary.framework.dSYM");
    assertThat(Files.exists(dsymPath), is(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbol("+[TestClass answer]", workspace, dsymPath);
  }

  @Test
  public void testAppleDynamicLibraryProducesDylib() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_shared", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#shared,macosx-x86_64",
        "--config",
        "cxx.cflags=-g");
    result.assertSuccess();

    Path output = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("Libraries/TestLibrary/TestLibrary#macosx-x86_64,shared")
        .resolve("libLibraries_TestLibrary_TestLibrary.dylib");
    assertThat(Files.exists(output), is(true));
  }

  @Test
  public void testAppleDynamicLibraryWithDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_shared", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#shared,macosx-x86_64,dwarf-and-dsym",
        "--config",
        "cxx.cflags=-g");
    result.assertSuccess();

    Path output = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("Libraries/TestLibrary/TestLibrary#macosx-x86_64,shared")
        .resolve("libLibraries_TestLibrary_TestLibrary.dylib");
    assertThat(Files.exists(output), is(true));

    Path dsymPath = tmp.getRoot()
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

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDebugFormat.DWARF.getFlavor())
                    .addFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    Path frameworksPath = frameworkPath.resolve("Frameworks");
    assertThat(Files.exists(frameworksPath), is(true));
    Path depPath =
        frameworksPath.resolve("TestLibraryDep.framework/TestLibraryDep");
    assertThat(Files.exists(depPath), is(true));
    assertThat(
        workspace.runCommand("file", depPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
    Path transitiveDepPath =
        frameworksPath.resolve(
            "TestLibraryTransitiveDep.framework/TestLibraryTransitiveDep");
    assertThat(Files.exists(transitiveDepPath), is(true));
    assertThat(
        workspace.runCommand("file", transitiveDepPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void frameworkDependenciesDoNotContainTransitiveDependencies() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDebugFormat.DWARF.getFlavor())
                    .addFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    Path frameworksPath = frameworkPath.resolve("Frameworks");
    assertThat(Files.exists(frameworksPath), is(true));
    Path depFrameworksPath =
        frameworksPath.resolve("TestLibraryDep.framework/Frameworks");
    assertThat(Files.exists(depFrameworksPath), is(false));
  }

  @Test
  public void noIncludeFrameworksDoesntContainFrameworkDependencies() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#" +
            "dwarf-and-dsym,framework,macosx-x86_64,no-include-frameworks");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath = workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s")
        .resolve("TestLibrary.framework"));
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

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_header_symlink_tree", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#" +
            "default," + CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path inputPath = workspace.getPath(buildTarget.getBasePath()).toRealPath();
    Path outputPath =
        workspace.getPath(BuildTargets.getGenPath(filesystem, buildTarget, "%s")).toRealPath();

    assertIsSymbolicLink(
        outputPath.resolve("TestLibrary/PublicHeader.h"),
        inputPath.resolve("PublicHeader.h"));
  }

  @Test
  public void testAppleLibraryIsHermetic() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_is_hermetic", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#static,iphonesimulator-x86_64");
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

    Path objectPath = BuildTargets
        .getGenPath(
            filesystem,
            target.withFlavors(
                ImmutableFlavor.of("compile-" + sanitize("TestClass.m.o")),
                ImmutableFlavor.of("iphonesimulator-x86_64")),
            "%s")
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

  private static void assertIsSymbolicLink(
      Path link,
      Path target) throws IOException {
    assertTrue(Files.isSymbolicLink(link));
    assertEquals(
        target,
        Files.readSymbolicLink(link));
  }
}
