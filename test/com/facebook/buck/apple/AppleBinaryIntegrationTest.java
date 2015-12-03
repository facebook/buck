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
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AppleBinaryIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testAppleBinaryBuildsBinary() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//Apps/TestApp:TestApp").assertSuccess();

    assertThat(Files.exists(getGenDir().resolve("Apps/TestApp/")), is(true));
    assertThat(
        workspace.runCommand("file", getGenDir().resolve("Apps/TestApp/TestApp").toString())
            .getStdout()
            .get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryAppBuildsApp() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//Apps/TestApp:TestApp#app").assertSuccess();

    assertThat(Files.exists(getGenDir().resolve("Apps/TestApp/")), is(true));
    Path appPath = getGenDir().resolve("Apps/TestApp/TestApp#app/TestApp.app/");
    assertThat(Files.exists(appPath.resolve("Info.plist")), is(true));
    assertThat(
        workspace.runCommand("file", appPath.resolve("TestApp").toString())
            .getStdout()
            .get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryWithSystemFrameworksBuildsSomething() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(
        ApplePlatform.builder().setName(ApplePlatform.Name.MACOSX).build()));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_system_frameworks_builds_something", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//Apps/TestApp:TestApp#macosx-x86_64").assertSuccess();

    assertTrue(Files.exists(getGenDir()));
  }

  @Test
  public void testAppleBinaryWithLibraryDependencyBuildsSomething() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(
        ApplePlatform.builder().setName(ApplePlatform.Name.MACOSX).build()));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_library_dependency_builds_something", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//Apps/TestApp:TestApp#macosx-x86_64").assertSuccess();

    assertTrue(Files.exists(getGenDir()));
  }

  @Test
  public void testAppleBinaryWithLibraryDependencyWithSystemFrameworksBuildsSomething()
      throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(
        ApplePlatform.builder().setName(ApplePlatform.Name.MACOSX).build()));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_library_dependency_with_system_frameworks_builds_something", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//Apps/TestApp:TestApp#macosx-x86_64").assertSuccess();

    assertTrue(Files.exists(getGenDir()));
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
  public void testAppleBinaryWithHeaderMaps() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "apple_binary_with_header_maps", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//Apps/TestApp:TestApp").assertSuccess();

    assertTrue(Files.exists(getGenDir()));
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

    ProjectWorkspace.ProcessResult first = workspace.runBuckCommand(
        workspace.getPath("first"),
        "build",
        "//Apps/TestApp:TestApp#iphonesimulator-x86_64");
    first.assertSuccess();

    ProjectWorkspace.ProcessResult second = workspace.runBuckCommand(
        workspace.getPath("second"),
        "build",
        "//Apps/TestApp:TestApp#iphonesimulator-x86_64");
    second.assertSuccess();

    MoreAsserts.assertContentsEqual(
        workspace.getPath(
            "first/buck-out/gen/Apps/TestApp/" +
                "TestApp#compile-TestClass.m.o,iphonesimulator-x86_64/TestClass.m.o"),
        workspace.getPath(
            "second/buck-out/gen/Apps/TestApp/" +
                "TestApp#compile-TestClass.m.o,iphonesimulator-x86_64/TestClass.m.o"));
    MoreAsserts.assertContentsEqual(
        workspace.getPath(
            "first/buck-out/gen/Apps/TestApp/TestApp#iphonesimulator-x86_64"),
        workspace.getPath(
            "second/buck-out/gen/Apps/TestApp/TestApp#iphonesimulator-x86_64"));
  }

  @Test
  public void testAppleBinaryBuildsFatBinaries() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    workspace.runBuckCommand(
        "build",
        "//:DemoAppBinary#iphonesimulator-i386,iphonesimulator-x86_64")
        .assertSuccess();
    Path output = getGenDir()
        .resolve("DemoAppBinary#iphonesimulator-i386,iphonesimulator-x86_64");
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand("lipo", output.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(
        lipoVerifyResult.getStderr().or(""),
        0,
        lipoVerifyResult.getExitCode());
  }

  @Test
  public void testFlavoredAppleBundleBuildsAndDsymFileCreated() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format=none",
        "//:DemoApp#dwarf-and-dsym")
        .assertSuccess();
    Path output = getGenDir()
        .resolve("DemoApp#dwarf-and-dsym/DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp");
    assertThat(Files.exists(output), Matchers.equalTo(true));
  }

  @Test
  public void testFlavoredAppleBundleBuildsAndDsymFileIsNotCreated() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format=dwarf_and_dsym",
        "//:DemoApp#no-debug")
        .assertSuccess();
    assertThat(
        Files.exists(
            getGenDir().resolve(
                "DemoApp#no-debug/DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp")),
        Matchers.equalTo(false));
    assertThat(Files.exists(
            getGenDir()
                .resolve(
                    "DemoApp#dwarf-and-dsym/DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp")),
        Matchers.equalTo(false));
    assertThat(Files.exists(
            getGenDir().resolve("DemoApp/DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp")),
        Matchers.equalTo(false));
  }

  @Test
  public void testAppleBundleDebugFormatRespectsDefaultConfigSettingDSYM() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format=dwarf_and_dsym",
        "//:DemoApp")
        .assertSuccess();
    assertThat(Files.exists(
            getGenDir().resolve(
                "DemoApp#dwarf-and-dsym/DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp")),
        Matchers.equalTo(true));
  }

  @Test
  public void testAppleBundleDebugFormatRespectsDefaultConfigSettingNoDebug() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    workspace.runBuckCommand("build",
        "--config",
        "apple.default_debug_info_format=none",
        "//:DemoApp")
        .assertSuccess();
    assertThat(Files.exists(
            getGenDir().resolve(
                "DemoApp#no-debug/DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp")),
        Matchers.equalTo(false));
  }

  private Path getGenDir() {
    return tmp.getRoot()
        .resolve(BuckConstant.GEN_DIR);
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
