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
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.StripStyle;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.FakeAppleDeveloperEnvironment;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AppleBundleIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException {
    filesystem = new ProjectFilesystem(tmp.getRoot());
  }

  private boolean checkCodeSigning(Path absoluteBundlePath)
      throws IOException, InterruptedException {
    if (!Files.exists(absoluteBundlePath)) {
      throw new NoSuchFileException(absoluteBundlePath.toString());
    }

    return CodeSigning.hasValidSignature(
        new DefaultProcessExecutor(new TestConsole()), absoluteBundlePath);
  }

  private void runSimpleApplicationBundleTestWithBuildTarget(String fqtn)
      throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget(fqtn);
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));

    // Non-Swift target shouldn't include Frameworks/
    assertFalse(Files.exists(appPath.resolve("Frameworks")));
  }

  @Test
  public void testDisablingBundleCaching() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    final String target = "//:DemoApp#iphonesimulator-x86_64,no-debug,no-include-frameworks";

    workspace.enableDirCache();
    workspace.runBuckBuild("-c", "apple.cache_bundles_and_packages=false", target).assertSuccess();
    workspace.runBuckCommand("clean");
    workspace.runBuckBuild("-c", "apple.cache_bundles_and_packages=false", target).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(target);
  }

  @Test
  public void simpleApplicationBundle() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    runSimpleApplicationBundleTestWithBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
  }

  @Test
  public void simpleApplicationBundleWithLinkerMapDoesNotAffectOutput()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    runSimpleApplicationBundleTestWithBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
  }

  @Test
  public void simpleApplicationBundleWithoutLinkerMapDoesNotAffectOutput()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    runSimpleApplicationBundleTestWithBuildTarget(
        "//:DemoApp#iphonesimulator-x86_64,no-debug,no-linkermap");
  }

  @Test
  public void simpleApplicationBundleWithCodeSigning() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_codesigning", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphoneos-arm64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    BuildTarget.builder(target)
                        .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                        .build(),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));

    // Do not match iOS profiles on tvOS targets.
    target = workspace.newBuildTarget("//:DemoApp#appletvos-arm64,no-debug");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertFailure();
    assertTrue(result.getStderr().contains("No valid non-expired provisioning profiles match"));

    // Match tvOS profile.
    workspace.addBuckConfigLocalOption(
        "apple", "provisioning_profile_search_path", "provisioning_profiles_tvos");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
  }

  @Test
  public void simpleApplicationBundleWithTargetCodeSigning() throws Exception {
    assertTargetCodesignToolIsUsedFor("//:DemoApp#iphoneos-arm64,no-debug");
  }

  @Test
  public void simpleFatApplicationBundleWithTargetCodeSigning() throws Exception {
    assertTargetCodesignToolIsUsedFor("//:DemoApp#iphoneos-arm64,iphoneos-armv7,no-debug");
  }

  private void assertTargetCodesignToolIsUsedFor(String fullyQualifiedName) throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_target_codesigning", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget(fullyQualifiedName);
    ProjectWorkspace.ProcessResult buildResult =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());

    // custom codesign tool exits with non-zero error code and prints a message to the stderr, so
    // that its use can be detected
    assertThat(buildResult.getStderr(), containsString("codesign was here"));
  }

  private NSDictionary verifyAndParsePlist(Path path) throws Exception {
    assertTrue(Files.exists(path));
    String resultContents = filesystem.readFileIfItExists(path).get();
    NSDictionary resultPlist =
        (NSDictionary) PropertyListParser.parse(resultContents.getBytes(Charsets.UTF_8));
    return resultPlist;
  }

  @Test
  public void simpleApplicationBundleWithDryRunCodeSigning() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_codesigning", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "dry_run_code_signing", "true");

    BuildTarget target =
        workspace.newBuildTarget("//:DemoAppWithFramework#iphoneos-arm64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    BuildTarget.builder(target)
                        .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                        .build(),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path codeSignResultsPath = appPath.resolve("BUCK_code_sign_entitlements.plist");
    assertTrue(Files.exists(codeSignResultsPath));

    NSDictionary resultPlist = verifyAndParsePlist(appPath.resolve("BUCK_pp_dry_run.plist"));
    assertEquals(new NSString("com.example.DemoApp"), resultPlist.get("bundle-id"));
    assertEquals(new NSString("12345ABCDE"), resultPlist.get("team-identifier"));
    assertEquals(
        new NSString("00000000-0000-0000-0000-000000000000"),
        resultPlist.get("provisioning-profile-uuid"));

    // Codesigning main bundle
    resultPlist = verifyAndParsePlist(appPath.resolve("BUCK_code_sign_args.plist"));
    assertEquals(new NSNumber(true), resultPlist.get("use-entitlements"));

    // Codesigning embedded framework bundle
    resultPlist =
        verifyAndParsePlist(
            appPath.resolve("Frameworks/DemoFramework.framework/BUCK_code_sign_args.plist"));
    assertEquals(new NSNumber(false), resultPlist.get("use-entitlements"));
  }

  @Test
  public void simpleApplicationBundleWithEmbeddedFrameworks() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_codesigning", tmp);
    workspace.setUp();

    BuildTarget appTarget =
        workspace.newBuildTarget(
            "//:DemoAppWithFramework#iphoneos-arm64,no-debug,include-frameworks");
    workspace.runBuckCommand("build", appTarget.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoAppWithFramework_output.expected"),
        BuildTargets.getGenPath(filesystem, appTarget, "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, appTarget, "%s")
                .resolve(appTarget.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(appTarget.getShortName())));
    assertTrue(checkCodeSigning(appPath));

    BuildTarget frameworkTarget =
        workspace.newBuildTarget("//:DemoFramework#iphoneos-arm64,no-debug,no-include-frameworks");
    Path frameworkPath =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, frameworkTarget, "%s")
                .resolve(frameworkTarget.getShortName() + ".framework"));
    assertFalse(checkCodeSigning(frameworkPath));

    Path embeddedFrameworkPath = appPath.resolve(Paths.get("Frameworks/DemoFramework.framework"));
    assertTrue(Files.exists(embeddedFrameworkPath.resolve(frameworkTarget.getShortName())));
    assertTrue(checkCodeSigning(embeddedFrameworkPath));
  }

  @Test
  public void simpleApplicationBundleWithCodeSigningAndEntitlements()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_codesigning_and_entitlements", tmp);
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

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_fat_application_bundle_no_debug", tmp);
    workspace.setUp();

    BuildTarget target =
        workspace.newBuildTarget("//:DemoApp#iphonesimulator-i386,iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            BuildTarget.builder(target)
                .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .build(),
            "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    BuildTarget.builder(target)
                        .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                        .build(),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path outputFile = appPath.resolve(target.getShortName());

    assertTrue(Files.exists(outputFile));
    ProcessExecutor.Result result =
        workspace.runCommand("lipo", outputFile.toString(), "-verify_arch", "i386", "x86_64");
    assertEquals(0, result.getExitCode());
  }

  @Test
  public void bundleHasOutputPath() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertEquals(
        String.format("%s %s", target.getFullyQualifiedName(), appPath), result.getStdout().trim());
  }

  @Test
  public void extensionBundle() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_extension", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoExtension#no-debug");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path extensionPath =
        BuildTargets.getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".appex");
    assertEquals(
        String.format("%s %s", target.getFullyQualifiedName(), extensionPath),
        result.getStdout().trim());

    result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
    Path outputBinary = workspace.getPath(extensionPath.resolve(target.getShortName()));
    assertTrue(
        String.format(
            "Extension binary could not be found inside the appex dir [%s].", outputBinary),
        Files.exists(outputBinary));
  }

  @Test
  public void appBundleWithExtensionBundleDependency() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_app_with_extension", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoAppWithExtension#no-debug");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertEquals(
        String.format("%s %s", target.getFullyQualifiedName(), appPath), result.getStdout().trim());

    result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("DemoAppWithExtension"))));
    assertTrue(
        Files.exists(
            workspace.getPath(appPath.resolve("PlugIns/DemoExtension.appex/DemoExtension"))));
  }

  @Test
  public void bundleBinaryHasDsymBundle() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_dwarf_and_dsym", tmp);
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

    Path bundlePath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    BuildTarget.builder(target)
                        .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                        .build(),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path dwarfPath =
        bundlePath.getParent().resolve("DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp");
    Path binaryPath = bundlePath.resolve("DemoApp");
    assertTrue(Files.exists(dwarfPath));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForMain(workspace, dwarfPath);

    ProcessExecutor.Result result =
        workspace.runCommand(
            "dsymutil", "-o", binaryPath.toString() + ".test.dSYM", binaryPath.toString());

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

  @Test
  public void bundleBinaryHasLinkerMapFile() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            target
                .withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .withAppendedFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor()),
            "%s"));

    BuildTarget binaryWithLinkerMap =
        workspace.newBuildTarget("//:DemoAppBinary#iphonesimulator-x86_64");

    Path binaryWithLinkerMapPath = BuildTargets.getGenPath(filesystem, binaryWithLinkerMap, "%s");
    Path linkMapPath = BuildTargets.getGenPath(filesystem, binaryWithLinkerMap, "%s-LinkMap.txt");

    assertThat(Files.exists(workspace.resolve(binaryWithLinkerMapPath)), Matchers.equalTo(true));
    assertThat(Files.exists(workspace.resolve(linkMapPath)), Matchers.equalTo(true));

    BuildTarget binaryWithoutLinkerMap =
        workspace
            .newBuildTarget("//:DemoAppBinary#iphonesimulator-x86_64")
            .withAppendedFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());
    Path binaryWithoutLinkerMapPath =
        BuildTargets.getGenPath(filesystem, binaryWithoutLinkerMap, "%s");
    assertThat(
        Files.exists(workspace.resolve(binaryWithoutLinkerMapPath)), Matchers.equalTo(false));
  }

  public String runSimpleBuildWithDefinedStripStyle(StripStyle stripStyle) throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    BuildTarget target =
        workspace.newBuildTarget(
            "//:DemoApp#iphonesimulator-x86_64," + stripStyle.getFlavor().getName());
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

    Path bundlePath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    BuildTarget.builder(target)
                        .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                        .addFlavors(stripStyle.getFlavor())
                        .addFlavors(AppleDebugFormat.NONE.getFlavor())
                        .build(),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path binaryPath = bundlePath.resolve("DemoApp");

    ProcessExecutor.Result result = workspace.runCommand("nm", binaryPath.toString());
    return result.getStdout().orElse("");
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "app_bundle_with_resources", tmp);
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
        Matchers.matchesPattern(
            "Variant files have to be in a directory with name ending in '\\.lproj', "
                + "but '.*/cc/Localizable.strings' is not."));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_invalid_variant", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertFailure();
  }

  @Test
  public void defaultPlatformInBuckConfig() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "default_platform_in_buckconfig_app_bundle", tmp);
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

    Path appPath =
        BuildTargets.getGenPath(
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "default_platform_in_buckconfig_flavored_app_bundle", tmp);
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

    Path appPath =
        BuildTargets.getGenPath(
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_asset_catalogs_are_included_in_bundle", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        BuildTargets.getGenPath(
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

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_asset_catalogs_are_included_in_bundle", tmp);
    workspace.setUp();
    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoAppWithMoreThanOneIconAndLaunchImage#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName());
  }

  @Test
  public void appleBundleDoesNotPropagateIncludeFrameworkFlavors() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_app_with_extension", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoAppWithExtension#no-debug");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    BuckBuildLog buckBuildLog = workspace.getBuildLog();

    ImmutableSet<String> targetsThatShouldContainIncludeFrameworkFlavors =
        ImmutableSet.of("//:DemoAppWithExtension", "//:DemoExtension");

    ImmutableSet<Flavor> includeFrameworkFlavors =
        ImmutableSet.of(
            InternalFlavor.of("no-include-frameworks"), InternalFlavor.of("include-frameworks"));

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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "application_bundle_with_substitutions", tmp);
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

    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve(target.getShortName()))));

    NSDictionary plist =
        (NSDictionary)
            PropertyListParser.parse(
                Files.readAllBytes(workspace.getPath(appPath.resolve("Info.plist"))));
    assertThat(
        "Should contain xcode build version",
        (String) plist.get("DTXcodeBuild").toJavaObject(),
        not(emptyString()));
  }

  @Test
  public void infoPlistSubstitutionsAreAppliedToEntitlements()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "application_bundle_with_entitlements_substitutions", tmp);
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

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
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
  public void productNameChangesBundleAndBinaryNames() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "application_bundle_with_product_name", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertSuccess();

    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify();

    String productName = "BrandNewProduct";
    Path appPath =
        BuildTargets.getGenPath(
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "application_bundle_with_invalid_substitutions", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertFailure();
  }

  @Test
  public void resourcesAreCompiled() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_compiled_resources", tmp);
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

    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                BuildTarget.builder(target)
                    .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                    .build(),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("AppViewController.nib"))));
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Model.momd"))));
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Model2.momd"))));
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("DemoApp.scnassets"))));
  }

  @Test
  public void watchApplicationBundle() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "watch_application_bundle", tmp);
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

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
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

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "legacy_watch_application_bundle", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance(
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

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_embedded_framework", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
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
  public void onlyIncludesResourcesInBundlesWhichStaticallyLinkThem() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.IPHONESIMULATOR));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_embedded_framework_and_resources", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    BuildTarget.builder(target)
                        .addFlavors(AppleDebugFormat.NONE.getFlavor())
                        .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                        .build(),
                    "%s")
                .resolve(target.getShortName() + ".app"));

    String resourceName = "Resource.plist";
    assertFalse(Files.exists(appPath.resolve(resourceName)));

    Path frameworkPath = appPath.resolve("Frameworks/TestFramework.framework");
    assertTrue(Files.exists(frameworkPath.resolve(resourceName)));
  }

  @Test
  public void testTargetOutputForAppleBundle() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result;
    // test no-debug output
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    result = workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path appPath =
        BuildTargets.getGenPath(
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
    result = workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    appPath =
        BuildTargets.getGenPath(
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

  @Test
  public void resourcesFromOtherCellsCanBeProperlyIncluded() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "bundle_with_resources_from_other_cells", tmp);
    workspace.setUp();
    Path outputPath = workspace.buildAndReturnOutput("//:bundle#iphonesimulator-x86_64");
    assertTrue("Resource file should exist.", Files.isRegularFile(outputPath.resolve("file.txt")));
  }
}
