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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleTestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

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

    Path projectRoot = tmp.getRootPath().toRealPath();

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

  @Test
  public void shouldRefuseToRunAppleTestIfXctestNotPresent() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xctest", tmp);
    workspace.setUp();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(containsString(
        "Set xctool_path = /path/to/xctool or xctool_zip_target = //path/to:xctool-zip in the " +
        "[apple] section of .buckconfig to run this test"));
    workspace.runBuckCommand("test", "//:foo");
  }

  @Test
  public void successOnTestPassing() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xctest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("xctool"),
        Paths.get("xctool"));
    workspace.writeContentsToPath(
         "[apple]\n  xctool_path = xctool/bin/xctool\n",
         ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:foo");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));
  }

  @Test
  public void exitCodeIsCorrectOnTestFailure() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xctest_failure", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("xctool"),
        Paths.get("xctool"));
    workspace.writeContentsToPath(
         "[apple]\n  xctool_path = xctool/bin/xctool\n",
         ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:foo");
    result.assertSpecialExitCode("test should fail", 42);
    assertThat(
        result.getStderr(),
        containsString("0 Passed   0 Skipped   1 Failed   FooXCTest"));
    assertThat(
        result.getStderr(),
        containsString("FAILURE -[FooXCTest testTwoPlusTwoEqualsFive]: FooXCTest.m:9"));
  }

  @Test
  public void successOnAppTestPassing() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_with_host_app", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("xctool"),
        Paths.get("xctool"));
    workspace.writeContentsToPath(
         "[apple]\n  xctool_path = xctool/bin/xctool\n",
         ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:AppTest");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   AppTest"));
  }

  @Test
  public void exitCodeIsCorrectOnAppTestFailure() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_with_host_app_failure", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("xctool"),
        Paths.get("xctool"));
    workspace.writeContentsToPath(
         "[apple]\n  xctool_path = xctool/bin/xctool\n",
         ".buckconfig.local");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:AppTest");
    result.assertSpecialExitCode("test should fail", 42);
    assertThat(
        result.getStderr(),
        containsString("0 Passed   0 Skipped   1 Failed   AppTest"));
    assertThat(
        result.getStderr(),
        containsString("FAILURE -[AppTest testMagicValueShouldFail]: AppTest.m:13"));
  }

  @Test
  public void successOnOsxLogicTestPassing() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_osx_logic_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("xctool"),
        Paths.get("xctool"));
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:LibTest");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   LibTest"));
  }

  @Test
  public void buckTestOnLibTargetRunsTestTarget() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_osx_logic_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("xctool"),
        Paths.get("xctool"));
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:Lib");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   LibTest"));
  }

  @Test
  public void successOnOsxAppTestPassing() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_osx_app_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("xctool"),
        Paths.get("xctool"));
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:AppTest");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   AppTest"));
  }

  @Test
  public void buckTestOnAppTargetRunsTestTarget() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_osx_app_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("xctool"),
        Paths.get("xctool"));
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:App");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   AppTest"));
  }

  @Test
  public void successForAppTestWithXib() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "app_bundle_with_xib", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("xctool"),
        Paths.get("xctool"));
    workspace.writeContentsToPath(
         "[apple]\n  xctool_path = xctool/bin/xctool\n",
         ".buckconfig.local");

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:AppTest");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   AppTest"));
  }

  @Test
  public void successOnTestPassingWithXctoolZipTarget() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_test_xctool_zip_target", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("xctool"),
        Paths.get("xctool"));
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:foo");
    result.assertSuccess();
    assertThat(
        result.getStderr(),
        containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));
  }

  private static void assertIsSymbolicLink(
      Path link,
      Path target) throws IOException {
    assertTrue(Files.isSymbolicLink(link));
    assertTrue(Files.isSameFile(target, Files.readSymbolicLink(link)));
  }
}
