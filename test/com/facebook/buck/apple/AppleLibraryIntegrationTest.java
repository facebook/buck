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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.environment.Platform;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AppleLibraryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testAppleLibraryBuildsSomething() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#static,default");
    result.assertSuccess();

    assertTrue(Files.exists(tmp.getRootPath().resolve(BuckConstant.GEN_DIR)));
  }

  @Test
  public void testAppleLibraryBuildsForWatchOS() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#watchos-armv7k,static");
    result.assertSuccess();

    assertTrue(Files.exists(tmp.getRootPath().resolve(BuckConstant.GEN_DIR)));
  }

  @Test
  public void testAppleLibraryBuildsForWatchSimulator() throws IOException {
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHSIMULATOR));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#watchsimulator-i386,static");
    result.assertSuccess();

    assertTrue(Files.exists(tmp.getRootPath().resolve(BuckConstant.GEN_DIR)));
  }

  @Test
  public void testAppleLibraryBuildsSomethingUsingAppleCxxPlatform() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_builds_something", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#static,macosx-x86_64");
    result.assertSuccess();

    assertTrue(Files.exists(tmp.getRootPath().resolve(BuckConstant.GEN_DIR)));
  }

  @Test
  public void testAppleLibraryHeaderSymlinkTree() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_header_symlink_tree", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#" +
            "default," + CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = tmp.getRootPath().toRealPath();

    Path inputPath = projectRoot.resolve(
        buildTarget.getBasePath());
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(buildTarget, "%s"));

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

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64");
    result.assertSuccess();

    Path frameworkPath = tmp.getRootPath()
        .resolve(BuckConstant.GEN_DIR)
        .resolve(
            "Libraries/TestLibrary/TestLibrary#framework,include-frameworks,macosx-x86_64/" +
                "TestLibrary.framework");
    assertThat(Files.exists(frameworkPath), is(true));
    assertThat(Files.exists(frameworkPath.resolve("Contents/Info.plist")), is(true));
    Path libraryPath = frameworkPath.resolve("Contents/MacOS/TestLibrary");
    assertThat(Files.exists(libraryPath), is(true));
    assertThat(
        workspace.runCommand("file", libraryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void frameworkContainsFrameworkDependencies() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64");
    result.assertSuccess();

    Path frameworkPath = tmp.getRootPath()
        .resolve(BuckConstant.GEN_DIR)
        .resolve(
            "Libraries/TestLibrary/TestLibrary#framework,include-frameworks,macosx-x86_64/" +
                "TestLibrary.framework");
    assertThat(Files.exists(frameworkPath), is(true));
    Path frameworksPath = frameworkPath.resolve("Contents/Frameworks");
    assertThat(Files.exists(frameworksPath), is(true));
    Path depPath =
        frameworksPath.resolve("TestLibraryDep.framework/Contents/MacOS/TestLibraryDep");
    assertThat(Files.exists(depPath), is(true));
    assertThat(
        workspace.runCommand("file", depPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
    Path transitiveDepPath =
        frameworksPath.resolve(
            "TestLibraryTransitiveDep.framework/Contents/MacOS/TestLibraryTransitiveDep");
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

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64");
    result.assertSuccess();

    Path frameworkPath = tmp.getRootPath()
        .resolve(BuckConstant.GEN_DIR)
        .resolve(
            "Libraries/TestLibrary/TestLibrary#framework,include-frameworks,macosx-x86_64/" +
                "TestLibrary.framework");
    assertThat(Files.exists(frameworkPath), is(true));
    Path frameworksPath = frameworkPath.resolve("Contents/Frameworks");
    assertThat(Files.exists(frameworksPath), is(true));
    Path depFrameworksPath =
        frameworksPath.resolve("TestLibraryDep.framework/Contents/Frameworks");
    assertThat(Files.exists(depFrameworksPath), is(false));
  }

  @Test
  public void noIncludeFrameworksDoesntContainFrameworkDependencies() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64,no-include-frameworks");
    result.assertSuccess();

    Path frameworkPath = tmp.getRootPath()
        .resolve(BuckConstant.GEN_DIR)
        .resolve(
            "Libraries/TestLibrary/TestLibrary#framework,macosx-x86_64,no-include-frameworks/" +
                "TestLibrary.framework");
    assertThat(Files.exists(frameworkPath), is(true));
    assertThat(Files.exists(frameworkPath.resolve("Contents/Info.plist")), is(true));
    Path libraryPath = frameworkPath.resolve("Contents/MacOS/TestLibrary");
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

    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//Libraries/TestLibrary:TestLibrary#" +
            "default," + CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = tmp.getRootPath().toRealPath();

    Path inputPath = projectRoot.resolve(
        buildTarget.getBasePath());
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(buildTarget, "%s"));

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

    ProjectWorkspace.ProcessResult first = workspace.runBuckCommand(
        workspace.getPath("first"),
        "build",
        "//Libraries/TestLibrary:TestLibrary#static,iphonesimulator-x86_64");
    first.assertSuccess();

    ProjectWorkspace.ProcessResult second = workspace.runBuckCommand(
        workspace.getPath("second"),
        "build",
        "//Libraries/TestLibrary:TestLibrary#static,iphonesimulator-x86_64");
    second.assertSuccess();

    MoreAsserts.assertContentsEqual(
        workspace.getPath(
            "first/buck-out/gen/Libraries/TestLibrary/" +
                "TestLibrary#compile-TestClass.m.o,iphonesimulator-x86_64/TestClass.m.o"),
        workspace.getPath(
            "second/buck-out/gen/Libraries/TestLibrary/" +
                "TestLibrary#compile-TestClass.m.o,iphonesimulator-x86_64/TestClass.m.o"));
    MoreAsserts.assertContentsEqual(
        workspace.getPath(
            "first/buck-out/gen/Libraries/TestLibrary/" +
                "TestLibrary#iphonesimulator-x86_64,static/libTestLibrary.a"),
        workspace.getPath(
            "second/buck-out/gen/Libraries/TestLibrary/" +
                "TestLibrary#iphonesimulator-x86_64,static/libTestLibrary.a"));
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
