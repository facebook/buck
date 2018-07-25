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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AppleTestIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
  }

  @Test
  public void testAppleTestHeaderSymlinkTree() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_test_header_symlink_tree", tmp);
    workspace.setUp();

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:Test#iphonesimulator-x86_64,private-headers");
    ProcessResult result = workspace.runBuckCommand("build", buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = tmp.getRoot().toRealPath();

    Path inputPath = projectRoot.resolve(buildTarget.getBasePath());
    Path outputPath =
        projectRoot.resolve(BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s"));

    assertIsSymbolicLink(outputPath.resolve("Header.h"), inputPath.resolve("Header.h"));
    assertIsSymbolicLink(outputPath.resolve("Test/Header.h"), inputPath.resolve("Header.h"));
  }

  @Test
  public void testInfoPlistFromExportRule() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_test_info_plist_export_file", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo#iphonesimulator-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = Paths.get(tmp.getRoot().toFile().getCanonicalPath());

    BuildTarget appleTestBundleFlavoredBuildTarget =
        buildTarget.withFlavors(
            InternalFlavor.of("iphonesimulator-x86_64"),
            InternalFlavor.of("apple-test-bundle"),
            AppleDebugFormat.DWARF.getFlavor(),
            LinkerMapMode.NO_LINKER_MAP.getFlavor(),
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path outputPath =
        projectRoot.resolve(
            BuildTargetPaths.getGenPath(filesystem, appleTestBundleFlavoredBuildTarget, "%s"));
    Path bundlePath = outputPath.resolve("foo.xctest");
    Path infoPlistPath = bundlePath.resolve("Info.plist");

    assertTrue(Files.isDirectory(bundlePath));
    assertTrue(Files.isRegularFile(infoPlistPath));
  }

  @Test
  public void testSetsFrameworkSearchPathAndLinksCorrectly() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_test_framework_search_path", tmp);
    workspace.setUp();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo#iphonesimulator-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path projectRoot = Paths.get(tmp.getRoot().toFile().getCanonicalPath());

    BuildTarget appleTestBundleFlavoredBuildTarget =
        buildTarget.withFlavors(
            InternalFlavor.of("iphonesimulator-x86_64"),
            InternalFlavor.of("apple-test-bundle"),
            AppleDebugFormat.DWARF.getFlavor(),
            LinkerMapMode.NO_LINKER_MAP.getFlavor(),
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path outputPath =
        projectRoot.resolve(
            BuildTargetPaths.getGenPath(filesystem, appleTestBundleFlavoredBuildTarget, "%s"));
    Path bundlePath = outputPath.resolve("foo.xctest");
    Path testBinaryPath = bundlePath.resolve("foo");

    assertTrue(Files.isDirectory(bundlePath));
    assertTrue(Files.isRegularFile(testBinaryPath));
  }

  @Test
  public void testInfoPlistVariableSubstitutionWorksCorrectly() throws Exception {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_test_info_plist_substitution", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:foo#iphonesimulator-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("foo_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem,
            target.withAppendedFlavors(
                AppleDebugFormat.DWARF.getFlavor(),
                AppleTestDescription.BUNDLE_FLAVOR,
                LinkerMapMode.NO_LINKER_MAP.getFlavor(),
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
  }

  @Test
  public void testDefaultPlatformBuilds() throws Exception {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_default_platform", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:foo");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("foo_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem,
            target.withAppendedFlavors(
                AppleTestDescription.BUNDLE_FLAVOR,
                AppleDebugFormat.DWARF.getFlavor(),
                LinkerMapMode.NO_LINKER_MAP.getFlavor(),
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
  }

  @Test
  public void testLinkedAsMachOBundleWithNoDylibDeps() throws Exception {

    doTestLinkedAsMachOBundleWithNoDylibDeps(true);
  }

  @Test
  public void testLinkedUsingObjCLinkerFlag() throws Exception {

    doTestLinkedAsMachOBundleWithNoDylibDeps(false);
  }

  private void doTestLinkedAsMachOBundleWithNoDylibDeps(boolean useObjCLinkerFlag)
      throws Exception {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_with_deps", tmp);
    workspace.setUp();

    BuildTarget buildTarget = workspace.newBuildTarget("//:foo");
    workspace
        .runBuckCommand(
            "build",
            "--config",
            "apple.always_link_with_objc_flag=" + useObjCLinkerFlag,
            buildTarget.getFullyQualifiedName())
        .assertSuccess();

    workspace.verify(
        Paths.get("foo_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem,
            buildTarget.withAppendedFlavors(
                AppleDebugFormat.DWARF.getFlavor(),
                AppleTestDescription.BUNDLE_FLAVOR,
                LinkerMapMode.NO_LINKER_MAP.getFlavor(),
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path projectRoot = Paths.get(tmp.getRoot().toFile().getCanonicalPath());
    BuildTarget appleTestBundleFlavoredBuildTarget =
        buildTarget.withFlavors(
            InternalFlavor.of("apple-test-bundle"),
            AppleDebugFormat.DWARF.getFlavor(),
            LinkerMapMode.NO_LINKER_MAP.getFlavor(),
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path outputPath =
        projectRoot.resolve(
            BuildTargetPaths.getGenPath(filesystem, appleTestBundleFlavoredBuildTarget, "%s"));
    Path bundlePath = outputPath.resolve("foo.xctest");
    Path testBinaryPath = bundlePath.resolve("foo");

    ProcessExecutor.Result binaryFileTypeResult =
        workspace.runCommand("file", "-b", testBinaryPath.toString());
    assertEquals(0, binaryFileTypeResult.getExitCode());
    assertThat(
        binaryFileTypeResult.getStdout().orElse(""), containsString("Mach-O 64-bit bundle x86_64"));

    ProcessExecutor.Result otoolResult =
        workspace.runCommand("otool", "-L", testBinaryPath.toString());
    assertEquals(0, otoolResult.getExitCode());
    assertThat(otoolResult.getStdout().orElse(""), containsString("foo"));
    assertThat(otoolResult.getStdout().orElse(""), not(containsString("bar.dylib")));

    ProcessExecutor.Result nmResult = workspace.runCommand("nm", "-j", testBinaryPath.toString());
    assertEquals(0, nmResult.getExitCode());
    assertThat(nmResult.getStdout().orElse(""), containsString("_OBJC_CLASS_$_Foo"));
    if (useObjCLinkerFlag) {
      // -ObjC loaded Bar even though it wasn't referenced.
      assertThat(nmResult.getStdout().orElse(""), containsString("_OBJC_CLASS_$_Bar"));
    } else {
      // Bar is not referenced and should not be in the resulting binary.
      assertThat(nmResult.getStdout().orElse(""), not(containsString("_OBJC_CLASS_$_Bar")));
    }
  }

  @Test
  public void testWithResourcesCopiesResourceFilesAndDirs() throws Exception {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_with_resources", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:foo#iphonesimulator-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("foo_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem,
            target.withAppendedFlavors(
                AppleDebugFormat.DWARF.getFlavor(),
                AppleTestDescription.BUNDLE_FLAVOR,
                LinkerMapMode.NO_LINKER_MAP.getFlavor(),
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
  }

  @Test
  public void shouldRefuseToRunAppleTestIfXctestNotPresent() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_xctest", tmp);
    workspace.setUp();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        containsString(
            "Set xctool_path = /path/to/xctool or xctool_zip_target = //path/to:xctool-zip in the "
                + "[apple] section of .buckconfig to run this test"));
    workspace.runBuckCommand("test", "//:foo", "--config", "apple.xctool_path=does/not/exist");
  }

  @Test
  public void successOnTestPassing() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_xctest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");
    ProcessResult result = workspace.runBuckCommand("test", "//:foo");
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));
  }

  @Test
  public void skipsRunButBuildsTargetsForLegacyXCUITests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_xcuitest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");
    ProcessResult result = workspace.runBuckCommand("test", "//:LogicTest", "//:UITestLegacy");
    result.assertSuccess();
    workspace
        .getBuildLog()
        .assertTargetBuiltLocally("//:TestHostApp#dwarf,no-include-frameworks,strip-non-global");
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));
  }

  @Test
  public void canBuildAndRunXCUITest() throws IOException {
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.IPHONESIMULATOR));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_xcuitest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");
    ProcessResult result = workspace.runBuckCommand("test", "//:UITest");
    result.assertSuccess();
    workspace
        .getBuildLog()
        .assertTargetBuiltLocally("//:TestHostApp#dwarf,no-include-frameworks,strip-non-global");
    workspace
        .getBuildLog()
        .assertTargetBuiltLocally("//:TestTargetApp#dwarf,no-include-frameworks,strip-non-global");
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   AppTest"));
  }

  @Test
  public void slowTestShouldFailWithTimeout() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "slow_xc_tests_per_rule_timeout", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");
    ProcessResult result = workspace.runBuckCommand("test", "//:spinning");
    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    assertThat(result.getStderr(), containsString("Timed out after 100 ms running test command"));
  }

  @Test
  public void exitCodeIsCorrectOnTestFailure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_xctest_failure", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");
    ProcessResult result = workspace.runBuckCommand("test", "//:foo");
    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    assertThat(result.getStderr(), containsString("0 Passed   0 Skipped   1 Failed   FooXCTest"));
    assertThat(
        result.getStderr(),
        matchesPattern(
            "(?s).*FAILURE FooXCTest -\\[FooXCTest testTwoPlusTwoEqualsFive\\]:.*FooXCTest.m:9.*"));
  }

  @Test(timeout = 180000)
  public void successOnAppTestPassing() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_with_host_app", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");
    ProcessResult result = workspace.runBuckCommand("test", "//:AppTest");
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   AppTest"));
  }

  @Test(timeout = 180000)
  public void testWithHostAppWithDsym() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_with_host_app", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "//:AppTest",
            "--config",
            "cxx.cflags=-g",
            "--config",
            "apple.default_debug_info_format_for_binaries=DWARF_AND_DSYM",
            "--config",
            "apple.default_debug_info_format_for_libraries=DWARF_AND_DSYM",
            "--config",
            "apple.default_debug_info_format_for_tests=DWARF_AND_DSYM");
    result.assertSuccess();

    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   AppTest"));

    Path appTestDsym =
        tmp.getRoot()
            .resolve(filesystem.getBuckPaths().getGenDir())
            .resolve("AppTest#apple-test-bundle,dwarf-and-dsym,no-include-frameworks,no-linkermap")
            .resolve("AppTest.xctest.dSYM");
    AppleDsymTestUtil.checkDsymFileHasDebugSymbol(
        "-[AppTest testMagicValue]", workspace, appTestDsym);

    Path hostAppDsym =
        tmp.getRoot()
            .resolve(filesystem.getBuckPaths().getGenDir())
            .resolve("TestHostApp#dwarf-and-dsym,no-include-frameworks")
            .resolve("TestHostApp.app.dSYM");
    AppleDsymTestUtil.checkDsymFileHasDebugSymbol(
        "-[TestHostApp magicValue]", workspace, hostAppDsym);
  }

  @Test(timeout = 180000)
  public void exitCodeIsCorrectOnAppTestFailure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_test_with_host_app_failure", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "--config", "apple.xctool_path=fbxctest/bin/fbxctest", "//:AppTest");
    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    assertThat(result.getStderr(), containsString("0 Passed   0 Skipped   1 Failed   AppTest"));
    assertThat(
        result.getStderr(),
        matchesPattern(
            "(?s).*FAILURE AppTest -\\[AppTest testMagicValueShouldFail\\]:.*AppTest\\.m:13.*"));
  }

  @Test
  public void successOnOsxLogicTestPassing() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_osx_logic_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "--config", "apple.xctool_path=fbxctest/bin/fbxctest", "//:LibTest");
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   LibTest"));
  }

  @Test
  public void buckTestOnLibTargetRunsTestTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_osx_logic_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "--config", "apple.xctool_path=fbxctest/bin/fbxctest", "//:Lib");
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   LibTest"));
  }

  @Test(timeout = 180000)
  public void successForAppTestWithXib() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_compiled_resources", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");

    ProcessResult result = workspace.runBuckCommand("test", "//:AppTest");
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   AppTest"));
  }

  @Test
  public void successOnTestPassingWithFbXcTestZipTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_test_fbxctest_zip_target", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "--config", "apple.xctool_path=fbxctest/bin/fbxctest", "//:foo");
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));
  }

  // This test is disabled since the movement from xctool to fbxctest
  @Test
  public void testDependenciesLinking() throws IOException, InterruptedException {
    assumeTrue(false);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_dependencies_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "--config", "apple.xctool_path=fbxctest/bin/fbxctest", "//:App");
    result.assertSuccess();

    ProcessExecutor.Result hasSymbol =
        workspace.runCommand(
            "nm",
            workspace
                .getPath(
                    BuildTargetPaths.getGenPath(
                        filesystem,
                        workspace.newBuildTarget("#AppBinary#binary,iphonesimulator-x86_64"),
                        "AppBinary#apple-dsym,iphonesimulator-x86_64.dSYM"))
                .toString());

    assertThat(hasSymbol.getExitCode(), equalTo(0));
    assertThat(hasSymbol.getStdout().get(), containsString("U _OBJC_CLASS_$_Library"));
  }

  @Test
  public void environmentOverrideAffectsXctoolTest() throws Exception {

    // Our version of xctool doesn't pass through any environment variables, so just see if xctool
    // itself crashes.
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_xctest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    workspace
        .runBuckCommand("test", "--config", "apple.xctool_path=fbxctest/bin/fbxctest", "//:foo")
        .assertSuccess("normally the test should succeed");
    workspace.resetBuildLogFile();
    workspace
        .runBuckCommand(
            "test",
            "--config",
            "apple.xctool_path=fbxctest/bin/fbxctest",
            "--test-runner-env",
            "DYLD_INSERT_LIBRARIES=/non_existent_library_omg.dylib",
            "//:foo")
        .assertTestFailure("test should fail if i set incorrect dyld environment");
  }

  @Test
  public void environmentOverrideAffectsXctestTest() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_env", tmp);
    workspace.setUp();
    ProcessResult result;
    result =
        workspace.runBuckCommand(
            "test", "--config", "apple.xctest_platforms=macosx", "//:foo#macosx-x86_64");
    result.assertTestFailure("normally the test should fail");
    workspace.resetBuildLogFile();
    result =
        workspace.runBuckCommand(
            "test",
            "--config",
            "apple.xctest_platforms=macosx",
            "--test-runner-env",
            "FOO=bar",
            "//:foo#macosx-x86_64");
    result.assertSuccess("should pass when I pass correct environment");
  }

  @Test
  public void targetspecificEnvironmentOverrideAffectsXctestTest() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_env", tmp);
    workspace.setUp();
    ProcessResult result;
    result =
        workspace.runBuckCommand(
            "test", "--config", "apple.xctest_platforms=macosx", "//:foo#macosx-x86_64");
    result.assertTestFailure("normally the test should fail");
    workspace.resetBuildLogFile();
    result =
        workspace.runBuckCommand(
            "test",
            "--config",
            "apple.xctest_platforms=macosx",
            "--config",
            "testcase.set_targetspecific_env=True",
            "//:foo#macosx-x86_64");
    result.assertSuccess("should pass when I pass correct environment");
  }

  @Test
  public void appleTestWithoutTestHostShouldSupportMultiarch() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_xctest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    BuildTarget target =
        BuildTargetFactory.newInstance("//:foo#iphonesimulator-i386,iphonesimulator-x86_64");
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "--config",
            "apple.xctool_path=fbxctest/bin/fbxctest",
            target.getFullyQualifiedName());
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));

    result = workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path output =
        workspace
            .getDestPath()
            .resolve(Iterables.getLast(Splitter.on(' ').limit(2).split(result.getStdout().trim())));
    // check result is actually multiarch.
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand(
            "lipo", output.resolve("foo").toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(lipoVerifyResult.getStderr().orElse(""), 0, lipoVerifyResult.getExitCode());
  }

  @Test
  public void appleTestWithoutTestHostMultiarchShouldHaveMultiarchDsym() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_xctest", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    BuildTarget target =
        BuildTargetFactory.newInstance("//:foo#iphonesimulator-i386,iphonesimulator-x86_64");
    ProcessResult result =
        workspace.runBuckCommand(
            "build",
            "--config",
            "cxx.cflags=-g",
            "--config",
            "apple.xctool_path=fbxctest/bin/fbxctest",
            "--config",
            "apple.default_debug_info_format_for_binaries=DWARF_AND_DSYM",
            "--config",
            "apple.default_debug_info_format_for_libraries=DWARF_AND_DSYM",
            "--config",
            "apple.default_debug_info_format_for_tests=DWARF_AND_DSYM",
            target.getFullyQualifiedName());
    result.assertSuccess();

    BuildTarget libraryTarget =
        target.withAppendedFlavors(
            AppleTestDescription.LIBRARY_FLAVOR, CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR);
    Path output =
        workspace
            .getDestPath()
            .resolve(
                BuildTargetPaths.getGenPath(
                    filesystem,
                    libraryTarget.withAppendedFlavors(AppleDsym.RULE_FLAVOR),
                    "%s.dSYM"))
            .resolve("Contents/Resources/DWARF/" + libraryTarget.getShortName());
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand("lipo", output.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(lipoVerifyResult.getStderr().orElse(""), 0, lipoVerifyResult.getExitCode());
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForConcreteArchitectures(
        "-[FooXCTest testTwoPlusTwoEqualsFour]",
        workspace,
        output,
        Optional.of(ImmutableList.of("i386", "x86_64")));
  }

  @Test(timeout = 180000)
  public void appleTestWithTestHostShouldSupportMultiarch() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_test_with_host_app", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    BuildTarget target =
        BuildTargetFactory.newInstance("//:AppTest#iphonesimulator-i386,iphonesimulator-x86_64");
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "--config",
            "apple.xctool_path=fbxctest/bin/fbxctest",
            target.getFullyQualifiedName());
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   AppTest"));

    result = workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path output =
        workspace
            .getDestPath()
            .resolve(Iterables.getLast(Splitter.on(' ').limit(2).split(result.getStdout().trim())));
    // check result is actually multiarch.
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand(
            "lipo", output.resolve("AppTest").toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(lipoVerifyResult.getStderr().orElse(""), 0, lipoVerifyResult.getExitCode());
  }

  @Test
  public void testSwiftInTestTargetUsedByObjCInTestTarget() throws IOException {
    testSwiftScenario("apple_test_swift_test_case");
  }

  @Test
  public void testObjCUsesAppleLibraryWithSwiftSources() throws IOException {
    testSwiftScenario("apple_test_objc_uses_apple_library_with_swift_sources");
  }

  @Test
  public void testObjCUsesSwiftSourcesFromTestTarget() throws IOException {
    testSwiftScenario("apple_test_objc_uses_swift_from_test_target");
  }

  @Test
  public void testSwiftUsesAppleLibraryWithSwiftSources() throws IOException {
    testSwiftScenario("apple_test_swift_uses_apple_library_with_swift_sources");
  }

  @Test
  public void testSwiftUsesAppleLibraryWithObjCSources() throws IOException {
    testSwiftScenario("apple_test_swift_uses_apple_library_with_objc_sources");
  }

  @Test
  public void testObjCUsesAppleLibraryWithSwiftSourcesUsingPrivateIncludePrefix()
      throws IOException {
    testSwiftScenario("apple_test_objc_uses_apple_library_with_swift_sources_private_path");
  }

  @Test
  public void successXctoolZipWithCell() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "fbxctest_zip", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:foo", "cell//:bar");
    result.assertSuccess();
    System.err.println(result.getStderr());
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   BarXCTest"));
  }

  @Test
  public void successXctoolZipWithCellAndEmbeddedBuckout() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "fbxctest_zip", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "--config",
            "project.embedded_cell_buck_out_enabled=true",
            "//:foo",
            "cell//:bar");
    result.assertSuccess();
    System.err.println(result.getStderr());
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   FooXCTest"));
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   BarXCTest"));
  }

  private void testSwiftScenario(String scenarionName) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenarionName, tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");

    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(this).resolve("fbxctest"), Paths.get("fbxctest"));
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "--config", "apple.xctool_path=fbxctest/bin/fbxctest", "//:LibTest");
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed   0 Skipped   0 Failed   LibTest"));
  }

  private static void assertIsSymbolicLink(Path link, Path target) throws IOException {
    assertTrue(Files.isSymbolicLink(link));
    assertTrue(Files.isSameFile(target, Files.readSymbolicLink(link)));
  }
}
