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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.ProcessExecutor;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleTestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testAppleTestHeaderSymlinkTree() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_header_symlink_tree", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTarget.builder("//Libraries/TestLibrary", "Test")
        .addFlavors(ImmutableFlavor.of("default"))
        .addFlavors(ImmutableFlavor.of("header-symlink-tree"))
        .build();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = Paths.get(tmp.getRootPath().toFile().getCanonicalPath());

    Path inputPath = projectRoot.resolve(
        buildTarget.getBasePath());
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(buildTarget, "%s"));

    assertIsSymbolicLink(
        outputPath.resolve("Header.h"),
        inputPath.resolve("Header.h"));
    assertIsSymbolicLink(
        outputPath.resolve("Test/Header.h"),
        inputPath.resolve("Header.h"));
  }

  @Test
  public void testInfoPlistFromExportRule() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_info_plist_export_file", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTarget.builder("//", "foo")
        .addFlavors(ImmutableFlavor.of("iphonesimulator-x86_64"))
        .build();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = Paths.get(tmp.getRootPath().toFile().getCanonicalPath());

    BuildTarget appleTestBundleFlavoredBuildTarget = BuildTarget.copyOf(buildTarget)
        .withFlavors(
            ImmutableFlavor.of("iphonesimulator-x86_64"),
            ImmutableFlavor.of("apple-test-bundle"));
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(
            appleTestBundleFlavoredBuildTarget,
            "%s"));
    Path bundlePath = outputPath.resolve("foo.xctest");
    Path infoPlistPath = bundlePath.resolve("Info.plist");

    assertTrue(Files.isDirectory(bundlePath));
    assertTrue(Files.isRegularFile(infoPlistPath));
  }

  @Test
  public void testSetsFrameworkSearchPathAndLinksCorrectly() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_framework_search_path", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTarget.builder("//", "foo")
        .addFlavors(ImmutableFlavor.of("iphonesimulator-x86_64"))
        .build();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = Paths.get(tmp.getRootPath().toFile().getCanonicalPath());

    BuildTarget appleTestBundleFlavoredBuildTarget = BuildTarget.copyOf(buildTarget)
        .withFlavors(
            ImmutableFlavor.of("iphonesimulator-x86_64"),
            ImmutableFlavor.of("apple-test-bundle"));
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(
            appleTestBundleFlavoredBuildTarget,
            "%s"));
    Path bundlePath = outputPath.resolve("foo.xctest");
    Path testBinaryPath = bundlePath.resolve("foo");

    assertTrue(Files.isDirectory(bundlePath));
    assertTrue(Files.isRegularFile(testBinaryPath));
  }

  @Test
  public void testInfoPlistVariableSubstitutionWorksCorrectly() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_info_plist_substitution", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTarget.builder("//", "foo")
        .addFlavors(ImmutableFlavor.of("iphonesimulator-x86_64"))
        .build();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();
    workspace.verify();
  }

  @Test
  public void testDefaultPlatformBuilds() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_default_platform", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTarget.builder("//", "foo")
        .build();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();
    workspace.verify();
  }

  @Test
  public void testLinkedAsMachOBundleWithNoDylibDeps() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_with_deps", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTarget.builder("//", "foo")
        .build();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();
    workspace.verify();

    Path projectRoot = Paths.get(tmp.getRootPath().toFile().getCanonicalPath());
    BuildTarget appleTestBundleFlavoredBuildTarget = BuildTarget.copyOf(buildTarget)
        .withFlavors(
            ImmutableFlavor.of("apple-test-bundle"));
    Path outputPath = projectRoot.resolve(
        BuildTargets.getGenPath(
            appleTestBundleFlavoredBuildTarget,
            "%s"));
    Path bundlePath = outputPath.resolve("foo.xctest");
    Path testBinaryPath = bundlePath.resolve("foo");

    ProcessExecutor.Result binaryFileTypeResult = workspace.runCommand(
        "file", "-b", testBinaryPath.toString());
    assertEquals(0, binaryFileTypeResult.getExitCode());
    assertThat(
        binaryFileTypeResult.getStdout().or(""),
        containsString("Mach-O 64-bit bundle x86_64"));

    ProcessExecutor.Result otoolResult = workspace.runCommand(
        "otool", "-L", testBinaryPath.toString());
    assertEquals(0, otoolResult.getExitCode());
    assertThat(
        otoolResult.getStdout().or(""),
        containsString("foo"));
    assertThat(
        otoolResult.getStdout().or(""),
        not(containsString("bar.dylib")));

    ProcessExecutor.Result nmResult = workspace.runCommand(
        "nm", "-j", testBinaryPath.toString());
    assertEquals(0, nmResult.getExitCode());
    assertThat(
        nmResult.getStdout().or(""),
        containsString("_OBJC_CLASS_$_Foo"));
    assertThat(
        nmResult.getStdout().or(""),
        containsString("_OBJC_CLASS_$_Bar"));
  }

  @Test
  public void testWithResourcesCopiesResourceFilesAndDirs() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_with_resources", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTarget.builder("//", "foo")
        .addFlavors(ImmutableFlavor.of("iphonesimulator-x86_64"))
        .build();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        buildTarget.getFullyQualifiedName());
    result.assertSuccess();
    workspace.verify();
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
