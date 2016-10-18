/*
 * Copyright 2013-present Facebook, Inc.
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
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.FakeAppleDeveloperEnvironment;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleBundleIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = new ProjectFilesystem(tmp.getRoot());
  }

  private boolean checkCodeSigning(Path absoluteBundlePath)
      throws IOException, InterruptedException {
    if (!Files.exists(absoluteBundlePath)) {
      throw new NoSuchFileException(absoluteBundlePath.toString());
    }

    return CodeSigning.hasValidSignature(
        new ProcessExecutor(new TestConsole()),
        absoluteBundlePath);
  }

  @Test
  public void simpleApplicationBundle() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_no_debug",
        tmp);
    workspace.setUp();

    BuildTarget target =
        workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));

    // Non-Swift target shouldn't include Frameworks/
    assertFalse(Files.exists(appPath.resolve("Frameworks")));
  }

  @Test
  public void simpleApplicationBundleWithCodeSigning() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_with_codesigning",
        tmp);
    workspace.setUp();

    BuildTarget target =
        workspace.newBuildTarget("//:DemoApp#iphoneos-arm64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));
  }

  @Test
  public void simpleApplicationBundleWithCodeSigningAndEntitlements()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_with_codesigning_and_entitlements",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#iphoneos-arm64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));
    workspace.assertFilesEqual(
        Paths.get("DemoApp.xcent.expected"),
        BuildTargets.getScratchPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s.xcent"));

    Path appPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));
  }

  @Test
  public void simpleApplicationBundleWithFatBinary() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_fat_application_bundle_no_debug",
        tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget(
        "//:DemoApp#iphonesimulator-i386,iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));
    Path outputFile = appPath.resolve(target.getShortName());

    assertTrue(Files.exists(outputFile));
    ProcessExecutor.Result result = workspace.runCommand(
        "lipo",
        outputFile.toString(),
        "-verify_arch", "i386", "x86_64");
    assertEquals(0, result.getExitCode());
  }

  @Test
  public void bundleHasOutputPath() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_no_debug",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path appPath = BuildTargets
        .getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s")
        .resolve(target.getShortName() + ".app");
    assertEquals(
        String.format("%s %s", target.getFullyQualifiedName(), appPath),
        result.getStdout().trim());
  }

  @Test
  public void extensionBundle() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_extension",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoExtension#no-debug");
    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path extensionPath = BuildTargets
        .getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s")
        .resolve(target.getShortName() + ".appex");
    assertEquals(
        String.format("%s %s", target.getFullyQualifiedName(), extensionPath),
        result.getStdout().trim());

    result = workspace
        .runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
    Path outputBinary = workspace.getPath(extensionPath.resolve(target.getShortName()));
    assertTrue(
        String.format(
            "Extension binary could not be found inside the appex dir [%s].",
            outputBinary),
        Files.exists(outputBinary));
  }

  @Test
  public void appBundleWithExtensionBundleDependency() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_app_with_extension",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoAppWithExtension#no-debug");
    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path appPath = BuildTargets
        .getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s")
        .resolve(target.getShortName() + ".app");
    assertEquals(
        String.format("%s %s", target.getFullyQualifiedName(), appPath),
        result.getStdout().trim());

    result = workspace
        .runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("DemoAppWithExtension"))));
    assertTrue(
        Files.exists(
            workspace.getPath(appPath.resolve("PlugIns/DemoExtension.appex/DemoExtension"))));
  }

  @Test
  public void bundleBinaryHasDsymBundle() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_dwarf_and_dsym",
        tmp);
    workspace.setUp();

    BuildTarget target =
        workspace.newBuildTarget("//:DemoApp#dwarf-and-dsym,iphonesimulator-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path bundlePath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));
    Path dwarfPath = bundlePath
        .getParent()
        .resolve("DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp");
    Path binaryPath = bundlePath.resolve("DemoApp");
    assertTrue(Files.exists(dwarfPath));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForMain(workspace, dwarfPath);

    ProcessExecutor.Result result = workspace.runCommand(
        "dsymutil",
        "-o",
        binaryPath.toString() + ".test.dSYM",
        binaryPath.toString());

    String dsymutilOutput = "";
    if (result.getStderr().isPresent()) {
      dsymutilOutput = result.getStderr().get();
    }
    if (dsymutilOutput.isEmpty()) {
      assertThat(result.getStdout().isPresent(), is(true));
      dsymutilOutput = result.getStdout().get();
    }
    assertThat(dsymutilOutput, containsString("warning: no debug symbols in executable"));
  }

  public String runSimpleBuildWithDefinedStripStyle(StripStyle stripStyle) throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_no_debug",
        tmp);
    workspace.setUp();

    BuildTarget target =
        workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64," +
            stripStyle.getFlavor().getName());
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .addFlavors(stripStyle.getFlavor())
                .addFlavors(AppleDebugFormat.NONE.getFlavor())
                .build(),
            "%s"));

    Path bundlePath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .addFlavors(stripStyle.getFlavor())
                    .addFlavors(AppleDebugFormat.NONE.getFlavor())
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));
    Path binaryPath = bundlePath.resolve("DemoApp");

    ProcessExecutor.Result result = workspace.runCommand(
        "nm",
        binaryPath.toString());
    return result.getStdout().or("");
  }

  @Test
  public void bundleBinaryWithStripStyleAllDoesNotContainAnyDebugInfo() throws Exception {
    String nmOutput = runSimpleBuildWithDefinedStripStyle(StripStyle.ALL_SYMBOLS);
    assertThat(nmOutput, not(containsString("t -[AppDelegate window]")));
    assertThat(nmOutput, not(containsString("S _OBJC_METACLASS_$_AppDelegate")));
  }

  @Test
  public void bundleBinaryWithStripStyleNonGlobalContainsOnlyGlobals() throws Exception {
    String nmOutput = runSimpleBuildWithDefinedStripStyle(StripStyle.NON_GLOBAL_SYMBOLS);
    assertThat(nmOutput, not(containsString("t -[AppDelegate window]")));
    assertThat(nmOutput, containsString("S _OBJC_METACLASS_$_AppDelegate"));
  }

  @Test
  public void bundleBinaryWithStripStyleDebuggingContainsGlobalsAndLocals() throws Exception {
    String nmOutput = runSimpleBuildWithDefinedStripStyle(StripStyle.DEBUGGING_SYMBOLS);
    assertThat(nmOutput, containsString("t -[AppDelegate window]"));
    assertThat(nmOutput, containsString("S _OBJC_METACLASS_$_AppDelegate"));
  }

  @Test
  public void appBundleWithResources() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "app_bundle_with_resources",
        tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));
  }

  @Test
  public void appBundleVariantDirectoryMustEndInLproj() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Variant files have to be in a directory with name ending in '.lproj', " +
            "but 'cc/Localizable.strings' is not.");

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "app_bundle_with_invalid_variant",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertFailure();
  }

  @Test
  public void defaultPlatformInBuckConfig() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "default_platform_in_buckconfig_app_bundle",
        tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:DemoApp");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor())
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath = BuildTargets
        .getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor())
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s")
        .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve(target.getShortName()))));
  }

  @Test
  public void defaultPlatformInBuckConfigWithFlavorSpecified() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "default_platform_in_buckconfig_flavored_app_bundle",
        tmp);
    workspace.setUp();
    BuildTarget target =
        workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath = BuildTargets
        .getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s")
        .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve(target.getShortName()))));
  }

  @Test
  public void appleAssetCatalogsAreIncludedInBundle() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "apple_asset_catalogs_are_included_in_bundle",
        tmp);
    workspace.setUp();
    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath = BuildTargets.getGenPath(
        filesystem,
        BuildTarget.builder(target)
            .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
            .build(),
        "%s");
    workspace.verify(Paths.get("DemoApp_output.expected"), outputPath);
    Path appPath = outputPath.resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Assets.car"))));
  }

  @Test
  public void appleAssetCatalogsWithMoreThanOneAppIconOrLaunchImageShouldFail() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("At most one asset catalog in the dependencies of");

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "apple_asset_catalogs_are_included_in_bundle",
        tmp);
    workspace.setUp();
    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoAppWithMoreThanOneIconAndLaunchImage#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName());
  }

  @Test
  public void appleBundleDoesNotPropagateIncludeFrameworkFlavors() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_app_with_extension",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoAppWithExtension#no-debug");
    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand("build", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    BuckBuildLog buckBuildLog = workspace.getBuildLog();

    ImmutableSet<String> targetsThatShouldContainIncludeFrameworkFlavors =
        ImmutableSet.of("//:DemoAppWithExtension", "//:DemoExtension");

    ImmutableSet<Flavor> includeFrameworkFlavors = ImmutableSet.of(
        ImmutableFlavor.of("no-include-frameworks"),
        ImmutableFlavor.of("include-frameworks"));

    for (BuildTarget builtTarget : buckBuildLog.getAllTargets()) {
      if (Sets.intersection(builtTarget.getFlavors(), includeFrameworkFlavors).isEmpty()) {
        assertThat(
            builtTarget.getUnflavoredBuildTarget().getFullyQualifiedName(),
            not(in(targetsThatShouldContainIncludeFrameworkFlavors)));
      } else {
        assertThat(
            builtTarget.getUnflavoredBuildTarget().getFullyQualifiedName(),
            in(targetsThatShouldContainIncludeFrameworkFlavors));
      }
    }
  }

  @Test
  public void infoPlistSubstitutionsAreApplied() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "application_bundle_with_substitutions",
        tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath = BuildTargets
        .getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s")
        .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve(target.getShortName()))));
  }

  @Test
  public void productNameChangesBundleAndBinaryNames() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "application_bundle_with_product_name",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertSuccess();

    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify();

    String productName = "BrandNewProduct";
    Path appPath = BuildTargets
        .getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s")
        .resolve(productName + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve(productName))));
  }

  @Test
  public void infoPlistWithUnrecognizedVariableFails() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "application_bundle_with_invalid_substitutions",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertFailure();
  }

  @Test
  public void resourcesAreCompiled() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "app_bundle_with_compiled_resources",
        tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath = BuildTargets
        .getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s")
        .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("AppViewController.nib"))));
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Model.momd"))));
  }

  @Test
  public void watchApplicationBundle() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "watch_application_bundle",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDebugFormat.NONE.getFlavor())
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));
    Path watchAppPath = appPath.resolve("Watch/DemoWatchApp.app");
    assertTrue(Files.exists(watchAppPath.resolve("DemoWatchApp")));

    assertTrue(
        Files.exists(
            watchAppPath.resolve("PlugIns/DemoWatchAppExtension.appex/DemoWatchAppExtension")));
    assertTrue(Files.exists(watchAppPath.resolve("Interface.plist")));
  }

  @Test
  public void legacyWatchApplicationBundle() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "legacy_watch_application_bundle",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance(
        "//:DemoApp#no-debug,iphonesimulator-x86_64,iphonesimulator-i386");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDebugFormat.NONE.getFlavor())
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));

    Path watchExtensionPath = appPath.resolve("Plugins/DemoWatchAppExtension.appex");
    assertTrue(Files.exists(watchExtensionPath.resolve("DemoWatchAppExtension")));
    assertTrue(Files.exists(watchExtensionPath.resolve("DemoWatchApp.app/DemoWatchApp")));
    assertTrue(Files.exists(watchExtensionPath.resolve("DemoWatchApp.app/_WatchKitStub/WK")));
    assertTrue(Files.exists(watchExtensionPath.resolve("DemoWatchApp.app/Interface.plist")));
  }

  @Test
  public void copiesFrameworkBundleIntoFrameworkDirectory() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.IPHONESIMULATOR));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "app_bundle_with_embedded_framework",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path appPath = workspace.getPath(
        BuildTargets
            .getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDebugFormat.NONE.getFlavor())
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app"));

    Path frameworkPath = appPath.resolve("Frameworks/TestFramework.framework");
    assertTrue(Files.exists(frameworkPath.resolve("TestFramework")));
  }

  @Test
  public void testTargetOutputForAppleBundle() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result;
    // test no-debug output
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    result = workspace.runBuckCommand(
        "targets",
        "--show-output",
        target.getFullyQualifiedName());
    result.assertSuccess();
    Path appPath = BuildTargets
        .getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s")
        .resolve(target.getShortName() + ".app");
    assertThat(
        result.getStdout(),
        Matchers.startsWith(target.getFullyQualifiedName() + " " + appPath.toString()));

    // test debug output
    target = BuildTargetFactory.newInstance("//:DemoApp#dwarf-and-dsym");
    result = workspace.runBuckCommand(
        "targets",
        "--show-output",
        target.getFullyQualifiedName());
    result.assertSuccess();
    appPath = BuildTargets
        .getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s")
        .resolve(target.getShortName() + ".app");
    assertThat(
        result.getStdout(),
        Matchers.startsWith(target.getFullyQualifiedName() + " " + appPath.toString()));
  }
}
