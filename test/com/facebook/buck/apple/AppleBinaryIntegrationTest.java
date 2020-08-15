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

import static com.facebook.buck.cxx.toolchain.CxxFlavorSanitizer.sanitize;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AppleBinaryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void testAppleBinaryBuildsBinaryWithLinkerMap() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(Paths.get(outputPath + "-LinkMap.txt")), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryBuildsBinaryWithoutLinkerMap() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
            .withFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(Paths.get(outputPath + "-LinkMap.txt")), is(false));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryUsesDefaultPlatformFromArgs() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_binary_with_platform", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(Paths.get(outputPath + "-LinkMap.txt")), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
    assertThat(
        workspace.runCommand("otool", "-hv", outputPath.toString()).getStdout().get(),
        containsString("ARM64"));
  }

  public void testAppleBinaryRespectsFlavorOverrides() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_binary_with_platform", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    // buckconfig override is ignored
    workspace
        .runBuckCommand(
            "build",
            target.getFullyQualifiedName(),
            "--config",
            "cxx.default_platform=doesnotexist")
        .assertSuccess();

    BuildTarget simTarget = target.withFlavors(InternalFlavor.of("iphonesimulator-x86_64"));
    workspace.runBuckCommand("build", simTarget.getFullyQualifiedName()).assertSuccess();
    Path simOutputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), simTarget, "%s"));
    assertThat(Files.exists(simOutputPath), is(true));
    assertThat(Files.exists(Paths.get(simOutputPath + "-LinkMap.txt")), is(true));
    assertThat(
        workspace.runCommand("file", simOutputPath.toString()).getStdout().get(),
        containsString("executable"));
    assertThat(
        workspace.runCommand("otool", "-hv", simOutputPath.toString()).getStdout().get(),
        containsString("X86_64"));

    BuildTarget fatTarget =
        target.withFlavors(
            InternalFlavor.of("iphonesimulator-x86_64"), InternalFlavor.of("iphonesimulator-i386"));
    workspace.runBuckCommand("build", fatTarget.getFullyQualifiedName()).assertSuccess();

    Path fatOutputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(fatOutputPath), is(true));
    assertThat(
        workspace.runCommand("file", fatOutputPath.toString()).getStdout().get(),
        containsString("executable"));
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand("lipo", fatOutputPath.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(lipoVerifyResult.getStderr().orElse(""), 0, lipoVerifyResult.getExitCode());
  }

  @Test
  public void testAppleBinaryUsesPlatformLinkerFlags() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestAppWithNonstandardMain");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void appleBinaryUsesDefaultPlatformForPlatformDeps() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_platform_deps", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
  }

  @Test
  public void appleBinaryExplicitPlatformForPlatformDeps() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_platform_deps", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#iphoneos-arm64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#iphoneos-armv7");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertFailure();
  }

  @Test
  public void testAppleBinarySupportsEntitlements() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    // iphonesimulator -- needs entitlements
    {
      BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestAppWithEntitlements");
      workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

      Path outputPath =
          workspace.getPath(
              BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
      assertThat(Files.exists(outputPath), is(true));
      assertThat(
          workspace.runCommand("file", outputPath.toString()).getStdout().get(),
          containsString("executable"));
      assertThat(
          workspace
              .runCommand("otool", "-s", "__TEXT", "__entitlements", outputPath.toString())
              .getStdout()
              .get(),
          containsString("Contents of (__TEXT,__entitlements) section"));
    }

    // macosx -- doesn't need entitlements
    {
      BuildTarget target =
          BuildTargetFactory.newInstance("//Apps/TestApp:TestAppWithEntitlements#macosx-x86_64");
      workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

      Path outputPath =
          workspace.getPath(
              BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
      assertThat(Files.exists(outputPath), is(true));
      assertThat(
          workspace.runCommand("file", outputPath.toString()).getStdout().get(),
          containsString("executable"));
      assertThat(
          workspace
              .runCommand("otool", "-s", "__TEXT", "__entitlements", outputPath.toString())
              .getStdout()
              .get(),
          not(containsString("Contents of (__TEXT,__entitlements) section")));
    }
  }

  @Test
  public void testAppleBinaryWithThinLTO() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_binary_with_thinlto", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.isDirectory(Paths.get(outputPath + "-lto")), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryWithThinLTOWithLibraryDependency() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_thinlto_library_dependency", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#app,macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#macosx-x86_64")
                    .withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                "%s"));
    assertThat(Files.isDirectory(Paths.get(outputPath + "-lto")), is(true));

    Path bundlePath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                target.withAppendedFlavors(
                    AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
                    AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                "%s/TestApp.app"));
    assertThat(Files.exists(bundlePath), is(true));
    Path binaryPath = bundlePath.resolve("Contents/MacOS/TestApp");
    assertThat(Files.exists(binaryPath), is(true));
    assertThat(
        workspace.runCommand("file", binaryPath.toString()).getStdout().get(),
        containsString("executable"));

    Path frameworkBundlePath = bundlePath.resolve("Contents/Frameworks/TestLibrary.framework");
    assertThat(Files.exists(frameworkBundlePath), is(true));
    Path frameworkBinaryPath = frameworkBundlePath.resolve("TestLibrary");
    assertThat(Files.exists(frameworkBinaryPath), is(true));
    assertThat(
        workspace.runCommand("file", frameworkBinaryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleBinaryWithFatLTO() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_binary_with_fatlto", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(Paths.get(outputPath + "-lto")), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryAppBuildsAppWithDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#app");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget =
        target.withFlavors(
            AppleBinaryDescription.APP_FLAVOR,
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR,
            AppleDebugFormat.DWARF_AND_DSYM.getFlavor());
    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                .resolve(appTarget.getShortName() + ".app"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(outputPath.resolve("Info.plist")), is(true));

    Path dsymPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                .resolve(appTarget.getShortName() + ".app.dSYM"));
    assertThat(Files.exists(dsymPath), is(true));
    assertThat(
        workspace
            .runCommand("file", outputPath.resolve(appTarget.getShortName()).toString())
            .getStdout()
            .get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryAppBuildsAppWithoutDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#app,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget =
        target.withFlavors(
            AppleBinaryDescription.APP_FLAVOR,
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR,
            AppleDebugFormat.NONE.getFlavor());
    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                .resolve(appTarget.getShortName() + ".app"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(Files.exists(outputPath.resolve("Info.plist")), is(true));

    Path dsymPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                .resolve(appTarget.getShortName() + ".app.dSYM"));
    assertThat(Files.exists(dsymPath), is(false));
  }

  @Test
  public void testAppleBinaryWithSystemFrameworksBuildsSomething() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_system_frameworks_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test(timeout = 120000)
  public void testAppleBinaryWithMultipleSwiftLibDepsHasASTPaths() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_multiple_swift_libs", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
            .withAppendedFlavors(InternalFlavor.of("macosx-x86_64"));
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));

    String nmOutput = workspace.runCommand("nm", "-a", outputPath.toString()).getStdout().get();
    assertTrue(findSwiftModuleASTInSymbolOutput("Bar", nmOutput));
    assertTrue(findSwiftModuleASTInSymbolOutput("Foo", nmOutput));
  }

  private boolean findSwiftModuleASTInSymbolOutput(String moduleName, String nmOutput) {
    Pattern barPattern =
        Pattern.compile("[0-9a-fA-F]+ a [a-zA-Z0-9\\-_/#,]+" + moduleName + ".swiftmodule");
    Matcher matcher = barPattern.matcher(nmOutput);
    return matcher.find();
  }

  @Test(timeout = 120000)
  public void testAppleBinaryWithLibraryDependencyWithSwiftSourcesBuildsSomething()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_library_dependency_with_swift_sources_builds_something", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
            .withAppendedFlavors(InternalFlavor.of("macosx-x86_64"));
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));

    // Check binary contains statically linked Swift runtime
    assertThat(
        workspace.runCommand("nm", outputPath.toString()).getStdout().get(),
        containsString("U _swift_bridgeObjectRetain"));
  }

  private String getMacDylibSymbolTable(
      String dylibTargetName, String dylibName, ProjectWorkspace workspace) throws Exception {
    BuildTarget dylibTarget =
        BuildTargetFactory.newInstance(dylibTargetName)
            .withAppendedFlavors(InternalFlavor.of("macosx-x86_64"), InternalFlavor.of("shared"));

    Path dylibDirOutputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), dylibTarget, "%s"));
    Path dylibOutputPath = dylibDirOutputPath.resolve(dylibName);
    assertThat(Files.exists(dylibOutputPath), is(true));
    assertThat(
        workspace.runCommand("file", dylibOutputPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));

    return workspace.runCommand("nm", dylibOutputPath.toString()).getStdout().get();
  }

  private String buildAndGetMacBinarySymbolTable(String appTargetName, ProjectWorkspace workspace)
      throws Exception {
    BuildTarget binaryTarget =
        BuildTargetFactory.newInstance(appTargetName)
            .withAppendedFlavors(InternalFlavor.of("macosx-x86_64"));
    workspace.runBuckCommand("build", binaryTarget.getFullyQualifiedName()).assertSuccess();

    Path binaryOutputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), binaryTarget, "%s"));
    assertThat(Files.exists(binaryOutputPath), is(true));
    assertThat(
        workspace.runCommand("file", binaryOutputPath.toString()).getStdout().get(),
        containsString("executable"));

    return workspace.runCommand("nm", binaryOutputPath.toString()).getStdout().get();
  }

  private void runLinkGroupTestForSingleDylib(
      String appTargetName, String dylibTargetName, String dylibName) throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_link_groups_single_dylib", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "link_groups_enabled", "true");

    // Check that binary contains symbols _only_ from A and not from B, C
    String binarySymbolTable = buildAndGetMacBinarySymbolTable(appTargetName, workspace);
    assertThat(binarySymbolTable, containsString("T _get_value_from_a"));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_b")));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_c")));

    // Check that dylib contains symbols _only_ from B, C and not from A
    String dylibSymbolTable = getMacDylibSymbolTable(dylibTargetName, dylibName, workspace);
    assertThat(dylibSymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(dylibSymbolTable, containsString("T _get_value_from_b"));
    assertThat(dylibSymbolTable, containsString("T _get_value_from_c"));
  }

  @Test
  public void testAppleBinaryWithLinkGroupsForExhaustiveMapWithSingleDylib() throws Exception {
    runLinkGroupTestForSingleDylib(
        "//Apps/TestApp:ExhaustiveApp", "//Apps/TestApp:ExhaustiveDylib", "ExhaustiveDylib.dylib");
  }

  @Test
  public void testAppleBinaryWithLinkGroupsForCatchAllMapWithSingleDylib() throws Exception {
    runLinkGroupTestForSingleDylib(
        "//Apps/TestApp:CatchAllApp", "//Apps/TestApp:CatchAllDylib", "CatchAllDylib.dylib");
  }

  @Test
  public void testAppleBinaryWithLinkGroupsWithLabelFilterWithTreeTraversal() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_link_groups_with_label_filter_with_tree_traversal", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "link_groups_enabled", "true");

    // Check that binary contains symbols _only_ from A and not from B, C (infra labelled)
    String binarySymbolTable = buildAndGetMacBinarySymbolTable("//Apps/TestApp:App", workspace);
    assertThat(binarySymbolTable, containsString("T _get_value_from_a"));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_b")));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_c")));

    // Check that dylib contains symbols _only_ from B, C (infra labelled) and not from A
    String dylibSymbolTable =
        getMacDylibSymbolTable("//Apps/TestApp:InfraDylib", "Infra.dylib", workspace);
    assertThat(dylibSymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(dylibSymbolTable, containsString("T _get_value_from_b"));
    assertThat(dylibSymbolTable, containsString("T _get_value_from_c"));
  }

  @Test(timeout = 120000)
  public void testAppleBinaryWithLinkGroupsWithLabelFilterWithNodeTraversal() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_link_groups_with_label_filter_with_node_traversal", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "link_groups_enabled", "true");

    // Check that binary contains symbols _only_ from C and not from A, B (product labelled)
    String binarySymbolTable = buildAndGetMacBinarySymbolTable("//Apps/TestApp:App", workspace);
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_b")));
    assertThat(binarySymbolTable, containsString("T _get_value_from_c"));

    // Check that dylib contains symbols _only_ from A, B (product labelled) and not from C
    String dylibSymbolTable =
        getMacDylibSymbolTable("//Apps/TestApp:ProductDylib", "Product.dylib", workspace);
    assertThat(dylibSymbolTable, containsString("T _get_value_from_a"));
    assertThat(dylibSymbolTable, containsString("T _get_value_from_b"));
    assertThat(dylibSymbolTable, not(containsString("T _get_value_from_c")));
  }

  @Test
  public void testAppleBinaryWithLinkGroupsWithPatternFilterWithTreeTraversal() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_link_groups_with_pattern_filter_with_tree_traversal", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "link_groups_enabled", "true");

    // Check that binary contains symbols _only_ from A and not from B, C (infra labelled)
    String binarySymbolTable = buildAndGetMacBinarySymbolTable("//Apps/TestApp:App", workspace);
    assertThat(binarySymbolTable, containsString("T _get_value_from_a"));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_b")));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_c")));

    // Check that dylib contains symbols _only_ from B, C (infra labelled) and not from A
    String dylibSymbolTable =
        getMacDylibSymbolTable("//Apps/TestApp:InfraDylib", "Infra.dylib", workspace);
    assertThat(dylibSymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(dylibSymbolTable, containsString("T _get_value_from_b"));
    assertThat(dylibSymbolTable, containsString("T _get_value_from_c"));
  }

  @Test
  public void testAppleBinaryWithLinkGroupsWithPatternFilterWithNodeTraversal() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_link_groups_with_pattern_filter_with_node_traversal", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "link_groups_enabled", "true");

    // Check that binary contains symbols _only_ from C and not from A, B (product labelled)
    String binarySymbolTable = buildAndGetMacBinarySymbolTable("//Apps/TestApp:App", workspace);
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_b")));
    assertThat(binarySymbolTable, containsString("T _get_value_from_c"));

    // Check that dylib contains symbols _only_ from A, B (product labelled) and not from C
    String dylibSymbolTable =
        getMacDylibSymbolTable("//Apps/TestApp:ProductDylib", "Product.dylib", workspace);
    assertThat(dylibSymbolTable, containsString("T _get_value_from_a"));
    assertThat(dylibSymbolTable, containsString("T _get_value_from_b"));
    assertThat(dylibSymbolTable, not(containsString("T _get_value_from_c")));
  }

  @Test
  public void testAppleBinaryWithLinkGroupsWithMixedFilterWithTreeTraversal() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_link_groups_with_mixed_filter_with_tree_traversal", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "link_groups_enabled", "true");

    // Check that binary contains symbols _only_ from A and not from B, C (infra labelled)
    String binarySymbolTable = buildAndGetMacBinarySymbolTable("//Apps/TestApp:App", workspace);
    assertThat(binarySymbolTable, containsString("T _get_value_from_a"));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_b")));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_c")));

    // Check that dylib contains symbols _only_ from B, C (infra labelled) and not from A
    String dylibSymbolTable =
        getMacDylibSymbolTable("//Apps/TestApp:InfraDylib", "Infra.dylib", workspace);
    assertThat(dylibSymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(dylibSymbolTable, containsString("T _get_value_from_b"));
    assertThat(dylibSymbolTable, containsString("T _get_value_from_c"));
  }

  @Test
  public void testAppleBinaryWithLinkGroupWithCuttingGenruleBranchEnabled() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_link_groups_with_genrule", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "link_groups_enabled", "true");

    String binarySymbolTable = buildAndGetMacBinarySymbolTable("//Apps/TestApp:TestApp", workspace);
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(binarySymbolTable, containsString("T _get_value_from_b"));

    String dylibSymbolTable =
        getMacDylibSymbolTable("//Apps/TestApp:Dylib", "Dylib.dylib", workspace);
    assertThat(dylibSymbolTable, containsString("T _get_value_from_a"));
    assertThat(dylibSymbolTable, not(containsString("T _get_value_from_b")));
  }

  @Test
  public void testAppleBinaryWithLinkGroupsWithMultipleDylibs() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_link_groups_multiples_dylibs", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "link_groups_enabled", "true");

    // Check that binary does _not_ contain any symbols from the libraries
    String binarySymbolTable = buildAndGetMacBinarySymbolTable("//Apps/TestApp:TestApp", workspace);
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_b")));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_c")));

    // Check that Dylib1 only contains symbols from //Apps/Libs:B
    String dylib1SymbolTable =
        getMacDylibSymbolTable("//Apps/TestApp:Dylib1", "Dylib1.dylib", workspace);
    assertThat(dylib1SymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(dylib1SymbolTable, containsString("T _get_value_from_b"));
    assertThat(dylib1SymbolTable, not(containsString("T _get_value_from_c")));

    // Check that Dylib2 contains symbols from //Apps/Libs:A and //Apps/Libs:C
    String dylib2SymbolTable =
        getMacDylibSymbolTable("//Apps/TestApp:Dylib2", "Dylib2.dylib", workspace);
    assertThat(dylib2SymbolTable, containsString("T _get_value_from_a"));
    assertThat(dylib2SymbolTable, not(containsString("T _get_value_from_b")));
    assertThat(dylib2SymbolTable, containsString("T _get_value_from_c"));
  }

  @Test
  public void testAppleBinaryWithLinkGroupsWithMultipleDylibsWithDuplicateSymbols()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_link_groups_multiples_dylibs_with_duplicate_symbols", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "link_groups_enabled", "true");

    // Check that binary does _not_ contain any symbols from the libraries
    String binarySymbolTable = buildAndGetMacBinarySymbolTable("//Apps/TestApp:TestApp", workspace);
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_b")));
    // NOTE: Even though C will be linked
    assertThat(binarySymbolTable, not(containsString("T _get_value_from_c")));

    // Check that Dylib1 contains symbols from //Apps/Libs:B and //Apps/Libs:C (marked DUPLICATE)
    String dylib1SymbolTable =
        getMacDylibSymbolTable("//Apps/TestApp:Dylib1", "Dylib1.dylib", workspace);
    assertThat(dylib1SymbolTable, not(containsString("T _get_value_from_a")));
    assertThat(dylib1SymbolTable, containsString("T _get_value_from_b"));
    assertThat(dylib1SymbolTable, containsString("T _get_value_from_c"));

    // Check that Dylib2 contains symbols from //Apps/Libs:A and //Apps/Libs:C (marked DUPLICATE)
    String dylib2SymbolTable =
        getMacDylibSymbolTable("//Apps/TestApp:Dylib2", "Dylib2.dylib", workspace);
    assertThat(dylib2SymbolTable, containsString("T _get_value_from_a"));
    assertThat(dylib2SymbolTable, not(containsString("T _get_value_from_b")));
    assertThat(dylib2SymbolTable, containsString("T _get_value_from_c"));
  }

  @Test
  public void testAppleBinaryWithLibraryDependencyBuildsSomething() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_library_dependency_builds_something", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
            .withAppendedFlavors(InternalFlavor.of("macosx-x86_64"));
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleBinaryWithLibraryDependencyBuildsApp() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_library_dependency_builds_something", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#app,macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path bundlePath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                target.withAppendedFlavors(
                    AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
                    AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                "%s/TestApp.app"));
    assertThat(Files.exists(bundlePath), is(true));
    Path binaryPath = bundlePath.resolve("Contents/MacOS/TestApp");
    assertThat(Files.exists(binaryPath), is(true));
    assertThat(
        workspace.runCommand("file", binaryPath.toString()).getStdout().get(),
        containsString("executable"));
    Path frameworkBundlePath = bundlePath.resolve("Contents/Frameworks/TestLibrary.framework");
    assertThat(Files.exists(frameworkBundlePath), is(true));
    Path frameworkBinaryPath = frameworkBundlePath.resolve("TestLibrary");
    assertThat(Files.exists(frameworkBinaryPath), is(true));
    assertThat(
        workspace.runCommand("file", frameworkBinaryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleBinaryWithLibraryDependencyWithSystemFrameworksBuildsSomething()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this,
            "apple_binary_with_library_dependency_with_system_frameworks_builds_something",
            tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
            .withAppendedFlavors(InternalFlavor.of("macosx-x86_64"));
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleLibraryPropagatesExportedPlatformLinkerFlags() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_library_dependency_builds_something", tmp);
    workspace.setUp();
    ProcessResult buildResult = workspace.runBuckCommand("build", "//Apps/TestApp:BadTestApp");
    buildResult.assertFailure();
    String stderr = buildResult.getStderr();
    assertTrue(stderr.contains("bad-flag"));
  }

  @Test
  public void testAppleBinaryHeaderSymlinkTree() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_header_symlink_tree", tmp);
    workspace.setUp();

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            "//Apps/TestApp:TestApp#default," + CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
    ProcessResult result = workspace.runBuckCommand("build", buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    AbsPath projectRoot = tmp.getRoot().toRealPath();

    AbsPath inputPath =
        projectRoot.resolve(
            buildTarget.getCellRelativeBasePath().getPath().toPath(projectRoot.getFileSystem()));
    AbsPath outputPath =
        projectRoot.resolve(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), buildTarget, "%s"));

    assertIsSymbolicLink(outputPath.resolve("Header.h"), inputPath.resolve("Header.h").getPath());
    assertIsSymbolicLink(
        outputPath.resolve("TestApp/Header.h"), inputPath.resolve("Header.h").getPath());
  }

  @Test
  public void testAppleBinaryWithHeaderMaps() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_header_maps", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("executable"));
  }

  @Test
  public void testAppleXcodeError() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    String expectedError =
        "Apps/TestApp/main.c:2:3: error: use of undeclared identifier 'SomeType'\n"
            + "  SomeType a;\n"
            + "  ^\n";
    String expectedWarning =
        "Apps/TestApp/main.c:3:10: warning: implicit conversion from 'double' to 'int' changes "
            + "value from 0.42 to 0 [-Wliteral-conversion]\n"
            + "  return 0.42;\n"
            + "  ~~~~~~ ^~~~\n";
    String expectedSummary = "1 warning and 1 error generated.\n";

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_xcode_error", tmp);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//Apps/TestApp:TestApp");
    buildResult.assertFailure();
    String stderr = buildResult.getStderr();

    assertTrue(
        stderr.contains(expectedError)
            && stderr.contains(expectedWarning)
            && stderr.contains(expectedSummary));
  }

  @Test
  public void testAppleBinaryIsHermetic() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_binary_is_hermetic", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestApp")
            .withAppendedFlavors(InternalFlavor.of("iphonesimulator-x86_64"));
    ProcessResult first =
        workspace.runBuckCommand(
            workspace.getPath("first"), "build", target.getFullyQualifiedName());
    first.assertSuccess();

    ProcessResult second =
        workspace.runBuckCommand(
            workspace.getPath("second"), "build", target.getFullyQualifiedName());
    second.assertSuccess();

    RelPath outputPath =
        BuildTargetPaths.getGenPath(
            FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(
                BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH),
            target.withFlavors(
                InternalFlavor.of("iphonesimulator-x86_64"),
                InternalFlavor.of("compile-" + sanitize("TestClass.m.o"))),
            "%s/TestClass.m.o");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(RelPath.get("first").resolve(outputPath)),
        workspace.getPath(RelPath.get("second").resolve(outputPath)));
    outputPath =
        BuildTargetPaths.getGenPath(
            FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(
                BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH),
            target,
            "%s");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(RelPath.get("first").resolve(outputPath)),
        workspace.getPath(RelPath.get("second").resolve(outputPath)));
  }

  private void runTestAppleBinaryWithDebugFormatIsHermetic(AppleDebugFormat debugFormat)
      throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_binary_is_hermetic", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Apps/TestApp:TestApp#iphonesimulator-x86_64," + debugFormat.getFlavor().getName());
    ProcessResult first =
        workspace.runBuckCommand(
            workspace.getPath("first"), "build", target.getFullyQualifiedName());
    first.assertSuccess();

    ProcessResult second =
        workspace.runBuckCommand(
            workspace.getPath("second"), "build", target.getFullyQualifiedName());
    second.assertSuccess();

    RelPath outputPath =
        BuildTargetPaths.getGenPath(
            FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(
                BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH),
            target.withFlavors(
                InternalFlavor.of("iphonesimulator-x86_64"),
                InternalFlavor.of("compile-" + sanitize("TestClass.m.o"))),
            "%s/TestClass.m.o");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(RelPath.get("first").resolve(outputPath)),
        workspace.getPath(RelPath.get("second").resolve(outputPath)));
    outputPath =
        BuildTargetPaths.getGenPath(
            FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(
                BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH),
            target.withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors()),
            "%s");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(RelPath.get("first").resolve(outputPath)),
        workspace.getPath(RelPath.get("second").resolve(outputPath)));

    if (debugFormat != AppleDebugFormat.DWARF) {
      RelPath strippedPath =
          BuildTargetPaths.getGenPath(
              FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(
                  BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH),
              target
                  .withoutFlavors(AppleDebugFormat.FLAVOR_DOMAIN.getFlavors())
                  .withAppendedFlavors(
                      StripStyle.NON_GLOBAL_SYMBOLS.getFlavor(), CxxStrip.RULE_FLAVOR),
              "%s");
      MoreAsserts.assertContentsEqual(
          workspace.getPath(RelPath.get("first").resolve(strippedPath)),
          workspace.getPath(RelPath.get("second").resolve(strippedPath)));
    }
  }

  @Test
  public void testAppleBinaryWithDwarfDebugFormatIsHermetic() throws IOException {
    runTestAppleBinaryWithDebugFormatIsHermetic(AppleDebugFormat.DWARF);
  }

  @Test
  public void testAppleBinaryWithDwarfAndDsymDebugFormatIsHermetic() throws IOException {
    runTestAppleBinaryWithDebugFormatIsHermetic(AppleDebugFormat.DWARF_AND_DSYM);
  }

  @Test
  public void testAppleBinaryWithNoneDebugFormatIsHermetic() throws IOException {
    runTestAppleBinaryWithDebugFormatIsHermetic(AppleDebugFormat.NONE);
  }

  @Test
  public void testAppleBinaryBuildsFatBinaries() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//:DemoAppBinary#iphonesimulator-i386,iphonesimulator-x86_64,no-linkermap");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path output =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(output), is(true));
    assertThat(
        workspace.runCommand("file", output.toString()).getStdout().get(),
        containsString("executable"));
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand("lipo", output.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(lipoVerifyResult.getStderr().orElse(""), 0, lipoVerifyResult.getExitCode());
  }

  @Test
  public void testAppleBinaryBuildsFatBinariesWithDsym() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//:DemoAppBinary#iphonesimulator-i386,iphonesimulator-x86_64,no-linkermap");
    BuildTarget targetToBuild =
        target.withAppendedFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor());
    BuildTarget dsymTarget = target.withAppendedFlavors(AppleDsym.RULE_FLAVOR);
    workspace.runBuckCommand("build", targetToBuild.getFullyQualifiedName()).assertSuccess();
    AbsPath output =
        AbsPath.of(
            workspace.getPath(
                AppleDsym.getDsymOutputPath(
                    dsymTarget.withoutFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor()),
                    workspace.getProjectFileSystem())));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolsForMainForConcreteArchitectures(
        workspace, output, Optional.of(ImmutableList.of("i386", "x86_64")));
  }

  @Test
  public void testFlavoredAppleBundleBuildsAndDsymFileCreatedAndBinaryIsStripped()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#dwarf-and-dsym");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace
        .runBuckCommand(
            "build",
            "--config",
            "apple.default_debug_info_format_for_binaries=none",
            target.getFullyQualifiedName())
        .assertSuccess();
    BuildTarget appTarget =
        target.withFlavors(
            AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    AbsPath output =
        AbsPath.of(
            workspace.getPath(
                BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                    .resolve(target.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(target.getShortName())));
    assertThat(Files.exists(output.getPath()), equalTo(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForMain(workspace, output);

    Path binaryOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                .resolve(target.getShortName() + ".app")
                .resolve(target.getShortName()));
    assertThat(Files.exists(binaryOutput), equalTo(true));

    ProcessExecutor.Result hasSymbol = workspace.runCommand("nm", binaryOutput.toString());
    String stdout = hasSymbol.getStdout().orElse("");
    assertThat(stdout, not(containsString("t -[AppDelegate window]")));
    assertThat(stdout, containsString("U _UIApplicationMain"));
  }

  @Test
  public void testFlavoredAppleBundleBuildsWithDwarfDebugFormatAndBinaryIsUnstripped()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#dwarf");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    BuildTarget appTarget =
        target.withFlavors(
            AppleDebugFormat.DWARF.getFlavor(), AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path output =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                .resolve(target.getShortName() + ".app")
                .resolve(target.getShortName()));
    assertThat(Files.exists(output), equalTo(true));
    ProcessExecutor.Result hasSymbol = workspace.runCommand("nm", output.toString());
    String stdout = hasSymbol.getStdout().orElse("");
    assertThat(stdout, containsString("t -[AppDelegate window]"));
    assertThat(stdout, containsString("U _UIApplicationMain"));
  }

  @Test
  public void testBuildingWithDwarfProducesAllCompileRulesOnDisk() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    Flavor platformFlavor = InternalFlavor.of("iphonesimulator-x86_64");

    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoApp")
            .withAppendedFlavors(AppleDebugFormat.DWARF.getFlavor());
    BuildTarget binaryTarget =
        BuildTargetFactory.newInstance("//:DemoAppBinary")
            .withAppendedFlavors(platformFlavor, AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget =
        target.withFlavors(
            AppleDebugFormat.DWARF.getFlavor(), AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);

    Path binaryOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                .resolve(target.getShortName() + ".app")
                .resolve(target.getShortName()));

    Path delegateFileOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    binaryTarget.withFlavors(
                        platformFlavor,
                        InternalFlavor.of("compile-" + sanitize("AppDelegate.m.o"))),
                    "%s")
                .resolve("AppDelegate.m.o"));

    Path mainFileOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    binaryTarget.withFlavors(
                        platformFlavor, InternalFlavor.of("compile-" + sanitize("main.m.o"))),
                    "%s")
                .resolve("main.m.o"));

    assertThat(Files.exists(binaryOutput), equalTo(true));
    assertThat(Files.exists(delegateFileOutput), equalTo(true));
    assertThat(Files.exists(mainFileOutput), equalTo(true));
  }

  @Test
  public void testBuildingWithNoDebugDoesNotProduceAllCompileRulesOnDisk() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    Flavor platformFlavor = InternalFlavor.of("iphonesimulator-x86_64");

    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoApp")
            .withAppendedFlavors(AppleDebugFormat.NONE.getFlavor());
    BuildTarget binaryTarget =
        BuildTargetFactory.newInstance("//:DemoAppBinary")
            .withAppendedFlavors(platformFlavor, AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget =
        target.withFlavors(
            AppleDebugFormat.NONE.getFlavor(), AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);

    Path binaryOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                .resolve(target.getShortName() + ".app")
                .resolve(target.getShortName()));

    Path delegateFileOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    binaryTarget.withFlavors(
                        platformFlavor,
                        InternalFlavor.of("compile-" + sanitize("AppDelegate.m.o")),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("AppDelegate.m.o"));

    Path mainFileOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    binaryTarget.withFlavors(
                        platformFlavor,
                        InternalFlavor.of("compile-" + sanitize("main.m.o")),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("main.m.o"));

    assertThat(Files.exists(binaryOutput), equalTo(true));
    assertThat(Files.exists(delegateFileOutput), equalTo(false));
    assertThat(Files.exists(mainFileOutput), equalTo(false));
  }

  @Test
  public void testBuildingWithDwarfAndDsymDoesNotProduceAllCompileRulesOnDisk() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    Flavor platformFlavor = InternalFlavor.of("iphonesimulator-x86_64");

    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoApp")
            .withAppendedFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor());
    BuildTarget binaryTarget =
        BuildTargetFactory.newInstance("//:DemoAppBinary")
            .withAppendedFlavors(platformFlavor, AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    BuildTarget appTarget =
        target.withFlavors(
            AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);

    Path binaryOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                .resolve(target.getShortName() + ".app")
                .resolve(target.getShortName()));

    Path delegateFileOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    binaryTarget.withFlavors(
                        platformFlavor,
                        InternalFlavor.of("compile-" + sanitize("AppDelegate.m.o")),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("AppDelegate.m.o"));

    Path mainFileOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    binaryTarget.withFlavors(
                        platformFlavor,
                        InternalFlavor.of("compile-" + sanitize("main.m.o")),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("main.m.o"));

    assertThat(Files.exists(binaryOutput), equalTo(true));
    assertThat(Files.exists(delegateFileOutput), equalTo(false));
    assertThat(Files.exists(mainFileOutput), equalTo(false));
  }

  @Test
  public void testFlavoredAppleBundleBuildsAndDsymFileIsNotCreatedAndBinaryIsStripped()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace
        .runBuckCommand(
            "build",
            "--config",
            "apple.default_debug_info_format_for_binaries=dwarf_and_dsym",
            target.getFullyQualifiedName())
        .assertSuccess();
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s")
                    .resolve(target.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(target.getShortName()))),
        equalTo(false));
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        workspace.getProjectFileSystem(),
                        target.withFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor()),
                        "%s")
                    .resolve(target.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(target.getShortName()))),
        equalTo(false));
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                        workspace.getProjectFileSystem(), target.withFlavors(), "%s")
                    .resolve(target.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(target.getShortName()))),
        equalTo(false));

    BuildTarget appTarget =
        target.withFlavors(
            AppleDebugFormat.NONE.getFlavor(), AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    Path binaryOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                .resolve(target.getShortName() + ".app")
                .resolve(target.getShortName()));
    assertThat(Files.exists(binaryOutput), equalTo(true));

    ProcessExecutor.Result hasSymbol = workspace.runCommand("nm", binaryOutput.toString());
    String stdout = hasSymbol.getStdout().orElse("");
    assertThat(stdout, not(containsString("t -[AppDelegate window]")));
    assertThat(stdout, containsString("U _UIApplicationMain"));
  }

  @Test
  public void testAppleBundleDebugFormatRespectsDefaultConfigSettingDSYM() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp");
    workspace
        .runBuckCommand(
            "build",
            "--config",
            "apple.default_debug_info_format_for_binaries=dwarf_and_dsym",
            target.getFullyQualifiedName())
        .assertSuccess();
    BuildTarget appTarget =
        target.withFlavors(
            AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
            AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR);
    AbsPath dwarfPath =
        AbsPath.of(
            workspace.getPath(
                BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                    .resolve(appTarget.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(appTarget.getShortName())));
    assertThat(Files.exists(dwarfPath.getPath()), equalTo(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForMain(workspace, dwarfPath);
  }

  @Test
  public void testAppleBundleDebugFormatRespectsDefaultConfigSettingNoDebug() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp");
    workspace
        .runBuckCommand(
            "build",
            "--config",
            "apple.default_debug_info_format_for_binaries=none",
            target.getFullyQualifiedName())
        .assertSuccess();
    BuildTarget appTarget = target.withFlavors(AppleDebugFormat.NONE.getFlavor());
    assertThat(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), appTarget, "%s")
                    .resolve(appTarget.getShortName() + ".app.dSYM")
                    .resolve("Contents/Resources/DWARF")
                    .resolve(appTarget.getShortName()))),
        equalTo(false));
  }

  @Test
  public void multiarchBinaryShouldCopyLinkMapOfComponents() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    BuildTarget singleArchI386Target =
        BuildTargetFactory.newInstance("//:DemoApp#iphonesimulator-i386");
    BuildTarget singleArchX8664Target =
        BuildTargetFactory.newInstance("//:DemoApp#iphonesimulator-x86_64");
    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoApp#iphonesimulator-i386,iphonesimulator-x86_64");

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "multiarch_binary_linkmap", tmp);
    workspace.setUp();
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    assertTrue(
        "Has link map for i386 arch.",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s-LinkMap")
                    .resolve(
                        singleArchI386Target.getShortNameAndFlavorPostfix() + "-LinkMap.txt"))));
    assertTrue(
        "Has link map for x86_64 arch.",
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s-LinkMap")
                    .resolve(
                        singleArchX8664Target.getShortNameAndFlavorPostfix() + "-LinkMap.txt"))));
  }

  @Test
  public void testBuildEmptySourceAppleBinaryDependsOnNonEmptyAppleLibrary() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_source_targets", tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:real-none2#macosx-x86_64");
    ProcessResult result = workspace.runBuckCommand("run", target.getFullyQualifiedName());
    result.assertSuccess();
    Assert.assertThat(result.getStdout(), equalTo("Hello"));
  }

  @Test
  public void testSwiftFilesInsideBinaryAreRebuiltWhenHeaderFileTheyDependOnChanges()
      throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "swift_header_dep_caching", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    BuildTarget target = workspace.newBuildTarget("//:binary");

    // Populate the cache and then reset the build log
    ProcessResult cachePopulatingResult =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());
    cachePopulatingResult.assertSuccess();

    // Reset us back to a clean state
    workspace.runBuckCommand("clean", "--keep-cache");

    // Now do the actual test - modify a file, do a build again, and confirm it rebuilt our swift
    workspace.copyFile("producer.h.new", "producer.h");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    workspace
        .getBuildLog()
        .assertTargetBuiltLocally("//:binary#iphonesimulator-x86_64,swift-compile");
  }

  @Test
  public void testAppleBinaryBuildsFatBinariesWithSwift() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "mixed_swift_objc_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();
    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//:DemoAppBinary#iphonesimulator-i386,iphonesimulator-x86_64,no-linkermap");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path output =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(output), is(true));
    assertThat(
        workspace.runCommand("file", output.toString()).getStdout().get(),
        containsString("executable"));
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand("lipo", output.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(lipoVerifyResult.getStderr().orElse(""), 0, lipoVerifyResult.getExitCode());
  }

  @Test
  public void testSwiftStdlibsAreCopiedWithSwiftOnlyInAppExtension() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "swift_stdlibs_in_extension", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//:TestApp#iphonesimulator-x86_64,no-linkermap");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    AbsPath frameworks =
        tmp.getRoot()
            .resolve(
                BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    BuildTargetFactory.newInstance(
                        "//:TestApp#dwarf-and-dsym,iphonesimulator-x86_64,no-include-frameworks,no-linkermap"),
                    "%s"))
            .resolve("TestApp.app")
            .resolve("Frameworks");

    assertTrue(
        "the Frameworks directory should be created within the app bundle",
        Files.exists(frameworks.getPath()));

    AbsPath libSwiftCore = frameworks.resolve("libswiftCore.dylib");
    assertTrue(
        "the Swift stdlibs should be copied to the Frameworks directory",
        Files.exists(libSwiftCore.getPath()));
  }

  @Test
  public void linkerExtraOutputsWork() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "linker_extra_outputs_work", tmp);
    workspace.setUp();
    Path result = workspace.buildAndReturnOutput(":map-extractor");
    String contents;

    contents = new String(Files.readAllBytes(result.resolve("bin")), StandardCharsets.UTF_8);
    assertThat(contents, not(emptyString()));

    contents = new String(Files.readAllBytes(result.resolve("shared_lib")), StandardCharsets.UTF_8);
    assertThat(contents, not(emptyString()));
  }

  private static void assertIsSymbolicLink(AbsPath link, Path target) throws IOException {
    assertTrue(Files.isSymbolicLink(link.getPath()));
    assertEquals(target, Files.readSymbolicLink(link.getPath()));
  }

  @Test(timeout = 120000)
  public void testAppleBinaryWithConditionalRelinking() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_binary_with_conditional_relinking", tmp);
    workspace.addBuckConfigLocalOption("apple", "conditional_relinking_enabled", "true");
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//Apps/TestApp:TestApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    // 1. On first build, check that we have a binary and have stored relinking info.

    Path binaryOutputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s"));
    assertThat(Files.exists(binaryOutputPath), is(true));
    assertThat(
        workspace.runCommand("file", binaryOutputPath.toString()).getStdout().get(),
        containsString("executable"));

    String originalBinaryHash = workspace.getProjectFileSystem().computeSha256(binaryOutputPath);

    // Check that relink info was generated
    Path relinkInfoPath =
        binaryOutputPath.resolveSibling(binaryOutputPath.getFileName() + ".relink-info.json");
    assertThat(Files.exists(relinkInfoPath), is(true));

    // The binary uses a special linker flag to generate a random UUID on each link, so if the
    // linker runs, this can be detected by observing different UUIDs. Hashing the executable to
    // determine if it was relinked does not work because the linker is deterministic.
    Optional<String> initialUuid = getDwarfdumpUuidOutput(workspace, binaryOutputPath);

    // 2. Add more symbols to the dylibs, binary should skip linking as ABI stayed the same

    workspace.addBuckConfigLocalOption("test", "add_symbols", "true");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    Optional<String> uuidAfterExpectedSkippedRelink =
        getDwarfdumpUuidOutput(workspace, binaryOutputPath);

    assertThat(Files.exists(relinkInfoPath), is(true));
    assertThat(initialUuid, equalTo(uuidAfterExpectedSkippedRelink)); // i.e., linking was skipped

    // 3. Swap symbols between dylibs, should be relinked as symbols would resolve to different
    // dylibs

    workspace.addBuckConfigLocalOption("test", "swap_symbols", "true");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    Optional<String> uuidAfterExpectedRelink = getDwarfdumpUuidOutput(workspace, binaryOutputPath);

    assertThat(Files.exists(relinkInfoPath), is(true));
    assertThat(
        initialUuid, not(equalTo(uuidAfterExpectedRelink))); // i.e., linking was _not_ skipped

    String binaryHashAfterSymbolSwap =
        workspace.getProjectFileSystem().computeSha256(binaryOutputPath);
    assertThat(originalBinaryHash, not(equalTo(binaryHashAfterSymbolSwap)));
  }

  /**
   * NOTE: This returns the output from dwarfdump with the additional binary path, _not_ just the
   * UUID part of the output.
   */
  private static Optional<String> getDwarfdumpUuidOutput(
      ProjectWorkspace workspace, Path executablePath) throws IOException, InterruptedException {
    return workspace.runCommand("dwarfdump", "--uuid", executablePath.toString()).getStdout();
  }

  @Test
  public void testAppleBinaryTargetSpecificSDKVersion() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_target_specific_sdk_version", tmp);
    workspace.addBuckConfigLocalOption("apple", "target_sdk_version_linker_flag", "true");
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    // Build binary without target specific SDK version, i.e., latest SDK deployment target

    BuildTarget nonSpecificTarget =
        BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#macosx-x86_64");
    ProcessResult result =
        workspace.runBuckCommand("build", nonSpecificTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path nonSpecificTargetPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem, nonSpecificTarget, "%s"));
    assertTrue(Files.exists(nonSpecificTargetPath));

    // Build binary with target specific SDK version (10.14 in BUCK file)

    BuildTarget sdkVersionTarget =
        BuildTargetFactory.newInstance("//Apps/TestApp:TargetSpecificVersionApp#macosx-x86_64");
    result = workspace.runBuckCommand("build", sdkVersionTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path sdkVersionTargetPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem, sdkVersionTarget, "%s"));
    assertTrue(Files.exists(sdkVersionTargetPath));

    // Extract loader command to verify deployment target

    Optional<String> nonSpecificTargetBuildVersion =
        AppleLibraryIntegrationTest.getOtoolLoaderCommandByName(
            workspace, nonSpecificTargetPath, "LC_BUILD_VERSION");
    assertTrue(nonSpecificTargetBuildVersion.isPresent());
    Optional<String> specificSDKTargetBuildVersion =
        AppleLibraryIntegrationTest.getOtoolLoaderCommandByName(
            workspace, sdkVersionTargetPath, "LC_BUILD_VERSION");
    assertTrue(specificSDKTargetBuildVersion.isPresent());

    // Verify that only target specific binary has deployment set to 10.14

    assertThat(
        nonSpecificTargetBuildVersion.get(), CoreMatchers.not(containsString("minos 10.14")));
    assertThat(specificSDKTargetBuildVersion.get(), containsString("minos 10.14"));
  }
}
