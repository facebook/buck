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

import static org.hamcrest.Matchers.allOf;
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
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.FakeAppleDeveloperEnvironment;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
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
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    String target = "//:DemoApp#iphonesimulator-x86_64,no-debug,no-include-frameworks";

    workspace.enableDirCache();
    workspace.runBuckBuild("-c", "apple.cache_bundles_and_packages=false", target).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace.runBuckBuild("-c", "apple.cache_bundles_and_packages=false", target).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(target);
  }

  @Test
  public void simpleApplicationBundle() throws IOException, InterruptedException {
    runSimpleApplicationBundleTestWithBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
  }

  @Test
  public void simpleApplicationBundleWithLinkerMapDoesNotAffectOutput()
      throws IOException, InterruptedException {
    runSimpleApplicationBundleTestWithBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
  }

  @Test
  public void simpleApplicationBundleWithoutLinkerMapDoesNotAffectOutput()
      throws IOException, InterruptedException {
    runSimpleApplicationBundleTestWithBuildTarget(
        "//:DemoApp#iphonesimulator-x86_64,no-debug,no-linkermap");
  }

  @Test
  public void simpleApplicationBundleWithCodeSigning() throws Exception {
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

    // Do not match iOS profiles on tvOS targets.
    target = workspace.newBuildTarget("//:DemoApp#appletvos-arm64,no-debug");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
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
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_target_codesigning", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget(fullyQualifiedName);
    ProcessResult buildResult = workspace.runBuckCommand("build", target.getFullyQualifiedName());

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
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
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

  // Specifying entitlments file via apple_binary entitlements_file
  @Test
  public void simpleApplicationBundleWithCodeSigningAndEntitlements()
      throws IOException, InterruptedException {
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
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
    workspace.assertFilesEqual(
        Paths.get("DemoApp.xcent.expected"),
        BuildTargets.getScratchPath(
            filesystem,
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s.xcent"));

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));
  }

  // Legacy method -- specifying entitlments file via info_plist_substitutions
  @Test
  public void simpleApplicationBundleWithCodeSigningAndEntitlementsUsingInfoPlistSubstitutions()
      throws IOException, InterruptedException {
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_codesigning_and_entitlements", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//:DemoAppUsingInfoPlistSubstitutions#iphoneos-arm64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.assertFilesEqual(
        Paths.get("DemoApp.xcent.expected"),
        BuildTargets.getScratchPath(
            filesystem,
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s.xcent"));

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));
  }

  @Test
  public void simpleApplicationBundleWithFatBinary() throws IOException, InterruptedException {
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
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertEquals(
        String.format("%s %s", target.getFullyQualifiedName(), appPath), result.getStdout().trim());
  }

  @Test
  public void extensionBundle() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_extension", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoExtension#no-debug");
    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path extensionPath =
        BuildTargets.getGenPath(
                filesystem,
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_app_with_extension", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoAppWithExtension#no-debug");
    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
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
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path bundlePath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path dwarfPath =
        bundlePath.getParent().resolve("DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp");
    Path binaryPath = bundlePath.resolve("DemoApp");
    assertTrue(Files.exists(dwarfPath));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbolForMain(workspace, dwarfPath);

    ProcessExecutor.Result result =
        workspace.runCommand("dsymutil", "-o", binaryPath + ".test.dSYM", binaryPath.toString());

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
            target.withAppendedFlavors(
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR,
                stripStyle.getFlavor(),
                AppleDebugFormat.NONE.getFlavor()),
            "%s"));

    Path bundlePath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR,
                        stripStyle.getFlavor(),
                        AppleDebugFormat.NONE.getFlavor()),
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "app_bundle_with_resources", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
  }

  @Test
  public void appBundleWithPlatformBinaryWithResources() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "app_bundle_with_resources", tmp);
    workspace.setUp();

    BuildTarget target =
        workspace.newBuildTarget("//:DemoAppWithPlatformBinary#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        Paths.get("DemoAppWithPlatformBinary_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
  }

  @Test
  public void appBundleWithConflictingFileAndFolderResources() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_conflicting_resources", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertFailure();
  }

  @Test
  public void appBundleWithConflictingNestedFolderResources() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_conflicting_nested_resources", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertFailure();
  }

  @Test
  public void appBundleWithConflictingFilenamesInNestedFolders() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_conflicting_filenames_in_nested_folders", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace.verify(
        Paths.get("DemoApp_output.expected"),
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(
                AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
  }

  @Test
  public void appBundleVariantDirectoryMustEndInLproj() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_invalid_variant", tmp);
    workspace.setUp();
    ProcessResult processResult =
        workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        allOf(
            containsString("Variant files have to be in a directory with name ending in '.lproj',"),
            containsString("/cc/Localizable.strings' is not.")));
  }

  @Test
  public void defaultPlatformInBuckConfig() throws Exception {
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
            target.withAppendedFlavors(
                AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                target.withAppendedFlavors(
                    AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
                    AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve(target.getShortName()))));
  }

  @Test
  public void defaultPlatformInBuckConfigWithFlavorSpecified() throws Exception {
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
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve(target.getShortName()))));
  }

  @Test
  public void appleAssetCatalogsAreIncludedInBundle() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_asset_catalogs_are_included_in_bundle", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        BuildTargets.getGenPath(
            filesystem,
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s");
    workspace.verify(Paths.get("DemoApp_output.expected"), outputPath);
    Path appPath = outputPath.resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Assets.car"))));
  }

  @Test
  public void generatedAppleAssetCatalogsAreIncludedInBundle() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_asset_catalogs_are_included_in_bundle", tmp);
    workspace.setUp();
    BuildTarget appTarget = BuildTargetFactory.newInstance("//:CombinedAssetsApp#no-debug");
    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//:MakeCombinedAssets");
    BuildTarget assetTarget = appTarget.withAppendedFlavors(AppleAssetCatalog.FLAVOR);
    workspace.runBuckCommand("build", appTarget.getFullyQualifiedName()).assertSuccess();

    // Check that the genrule was invoked
    workspace.getBuildLog().assertTargetBuiltLocally(genruleTarget.getFullyQualifiedName());

    // Check the actool output: Merged.bundle/Assets.car
    assertFileInOutputContainsString(
        "Image2", workspace, assetTarget, "%s/Merged.bundle/Assets.car");

    // Check the app package: Assets.car
    assertFileInOutputContainsString(
        "Image2",
        workspace,
        appTarget.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
        "%s/" + appTarget.getShortName() + ".app/Assets.car");
  }

  @Test
  public void appleAssetCatalogsWithCompilationOptions() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_asset_catalogs_are_included_in_bundle", tmp);
    workspace.setUp();
    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoAppWithAssetCatalogCompilationOptions#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
  }

  private void assertFileInOutputContainsString(
      String needle, ProjectWorkspace workspace, BuildTarget target, String genPathFormat)
      throws IOException {
    Path outputPath = BuildTargets.getGenPath(filesystem, target, genPathFormat);
    Path path = workspace.getPath(outputPath);
    assertTrue(Files.exists(path));
    String contents = workspace.getFileContents(outputPath);
    assertTrue(contents.contains(needle));
  }

  @Test
  public void appleAssetCatalogsWithMoreThanOneAppIconOrLaunchImageShouldFail() throws IOException {

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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_app_with_extension", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoAppWithExtension#no-debug");
    ProcessResult result =
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
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
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
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
    workspace.assertFilesEqual(
        Paths.get("DemoApp.xcent.expected"),
        BuildTargets.getScratchPath(
            filesystem,
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s.xcent"));

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));
  }

  @Test
  public void productNameChangesBundleAndBinaryNames() throws IOException {
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
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(productName + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve(productName))));
  }

  @Test
  public void infoPlistWithUnrecognizedVariableFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "application_bundle_with_invalid_substitutions", tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertFailure();
  }

  @Test
  public void resourcesAreCompiled() throws Exception {
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
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("AppViewController.nib"))));
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Model.momd"))));
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Model2.momd"))));
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("DemoApp.scnassets"))));
  }

  @Test
  public void watchApplicationBundle() throws IOException {
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
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
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
  public void legacyWatchApplicationBundle() throws IOException {
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
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
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
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));

    Path frameworkPath = appPath.resolve("Frameworks/TestFramework.framework");
    assertTrue(Files.exists(frameworkPath.resolve("TestFramework")));
  }

  @Test
  public void onlyIncludesResourcesInBundlesWhichStaticallyLinkThem() throws Exception {
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
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));

    String resourceName = "Resource.plist";
    assertFalse(Files.exists(appPath.resolve(resourceName)));

    Path frameworkPath = appPath.resolve("Frameworks/TestFramework.framework");
    assertTrue(Files.exists(frameworkPath.resolve(resourceName)));
  }

  @Test
  public void testTargetOutputForAppleBundle() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    ProcessResult result;
    // test no-debug output
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    result = workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    Path appPath =
        BuildTargets.getGenPath(
                filesystem,
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertThat(
        result.getStdout(), Matchers.startsWith(target.getFullyQualifiedName() + " " + appPath));

    // test debug output
    target = BuildTargetFactory.newInstance("//:DemoApp#dwarf-and-dsym");
    result = workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    appPath =
        BuildTargets.getGenPath(
                filesystem,
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertThat(
        result.getStdout(), Matchers.startsWith(target.getFullyQualifiedName() + " " + appPath));
  }

  @Test
  public void macAppWithExtraBinary() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_osx_app_with_extra_binary", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:App#no-debug");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    buildResult.assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path AppBinaryPath = appPath.resolve("Contents/MacOS/App");
    Path WorkerBinaryPath = appPath.resolve("Contents/MacOS/Worker");
    assertTrue(Files.exists(AppBinaryPath));
    assertTrue(Files.exists(WorkerBinaryPath));
  }

  @Test
  public void macAppWithXPCService() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_osx_app_with_xpc_service", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:App#no-debug");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    buildResult.assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path XPCServicePath = appPath.resolve("Contents/XPCServices/Service.xpc");
    Path XPCServiceBinaryPath = XPCServicePath.resolve("Contents/MacOS/Service");
    Path XPCServiceInfoPlistPath = XPCServicePath.resolve("Contents/Info.plist");
    assertTrue(Files.exists(XPCServiceBinaryPath));
    assertTrue(Files.exists(XPCServiceInfoPlistPath));
  }

  @Test
  public void macAppWithPlugin() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_osx_app_with_plugin", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:App#no-debug");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    buildResult.assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path pluginPath = appPath.resolve("Contents/PlugIns/Plugin.plugin");
    Path pluginBinaryPath = pluginPath.resolve("Contents/MacOS/Plugin");
    Path pluginInfoPlistPath = pluginPath.resolve("Contents/Info.plist");
    assertTrue(Files.exists(pluginBinaryPath));
    assertTrue(Files.exists(pluginInfoPlistPath));
  }

  @Test
  public void macAppWithPrefPane() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_osx_app_with_prefpane", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:App#no-debug");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    buildResult.assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path prefPanePath = appPath.resolve("Contents/Resources/PrefPane.prefPane");
    Path prefPaneBinaryPath = prefPanePath.resolve("Contents/MacOS/PrefPane");
    Path prefPaneInfoPlistPath = prefPanePath.resolve("Contents/Info.plist");
    assertTrue(Files.exists(prefPaneBinaryPath));
    assertTrue(Files.exists(prefPaneInfoPlistPath));
  }

  @Test
  public void macAppWithQuickLook() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_osx_app_with_quicklook", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:App#no-debug");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    buildResult.assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path quicklookPath = appPath.resolve("Contents/Library/QuickLook/QuickLook.qlgenerator");
    Path quicklookBinaryPath = quicklookPath.resolve("Contents/MacOS/QuickLook");
    Path quicklookInfoPlistPath = quicklookPath.resolve("Contents/Info.plist");
    assertTrue(Files.exists(quicklookBinaryPath));
    assertTrue(Files.exists(quicklookInfoPlistPath));
  }

  @Test
  public void resourcesFromOtherCellsCanBeProperlyIncluded() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "bundle_with_resources_from_other_cells", tmp);
    workspace.setUp();
    Path outputPath = workspace.buildAndReturnOutput("//:bundle#iphonesimulator-x86_64");
    assertTrue("Resource file should exist.", Files.isRegularFile(outputPath.resolve("file.txt")));
  }

  @Test
  public void bundleTraversesAppleResourceResourcesFromDepsForAdditionalResources()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_resources_from_deps", tmp);
    workspace.setUp();
    Path outputPath = workspace.buildAndReturnOutput("//:bundle#iphonesimulator-x86_64");
    assertTrue(
        "Resource file should exist.",
        Files.isRegularFile(outputPath.resolve("other_resource.txt")));
  }

  @Test
  public void bundleTraversesAppleResourceResourcesFromPlatformDepsForAdditionalResources()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_resources_from_deps", tmp);
    workspace.setUp();
    Path outputPath = workspace.buildAndReturnOutput("//:mybundle#iphonesimulator-x86_64");
    assertTrue(
        "Resource file matching platform should exist.",
        Files.isRegularFile(outputPath.resolve("sim.txt")));
    assertFalse(
        "Resource file not matching platform should not exist.",
        Files.isRegularFile(outputPath.resolve("device.txt")));
  }

  @Test
  public void defaultBinaryIsUsedWhenOnTargetPlatformMismatch() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_platform_binary", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:bundle#iphoneos-armv7").assertFailure();
  }

  @Test
  public void binaryMatchingTargetPlatformIsUsed() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_platform_binary", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:bundle#iphonesimulator-x86_64").assertSuccess();
  }

  @Test
  public void defaultBinaryIsNotUsedWhenPlatformSpecificBinaryIsSpecified() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_platform_binary", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild("//:bundle_with_broken_default_binary#iphonesimulator-x86_64")
        .assertSuccess();
  }

  @Test
  public void errorMessageForBundleWithoutBinaryIsDisplayed() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_platform_binary", tmp);
    workspace.setUp();
    thrown.expectMessage(
        "Binary matching target platform iphonesimulator-x86_64 cannot be found"
            + " and binary default is not specified.");
    workspace.runBuckBuild("//:bundle_without_binary#iphonesimulator-x86_64").assertFailure();
  }

  @Test
  public void errorMessageForBundleWithMultipleMatchingBinariesIsDisplayed() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_platform_binary", tmp);
    workspace.setUp();
    thrown.expectMessage(
        "There must be at most one binary matching the target platform "
            + "iphonesimulator-x86_64 but all of [//:binary, //:binary] matched. "
            + "Please make your pattern more precise and remove any duplicates.");
    workspace
        .runBuckBuild("//:bundle_with_multiple_matching_binaries#iphonesimulator-x86_64")
        .assertFailure();
  }

  @Test
  public void crossCellApplicationBundle() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "simple_cross_cell_application_bundle/primary", tmp.newFolder());
    workspace.setUp();
    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "simple_cross_cell_application_bundle/secondary", tmp.newFolder());
    secondary.setUp();

    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));

    BuildTarget target =
        workspace.newBuildTarget("//:DemoApp#dwarf-and-dsym,iphonesimulator-x86_64,no_debug");
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
}
