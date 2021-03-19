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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
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
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.xml.sax.SAXException;

public class AppleBundleIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    assumeThat(Platform.detect(), is(Platform.MACOS));
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

  private ProjectWorkspace runApplicationBundleTestWithScenarioAndBuildTarget(
      String scenario, String fqtn) throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget(fqtn);
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));

    // Non-Swift target shouldn't include Frameworks/
    assertFalse(Files.exists(appPath.resolve("Frameworks")));

    return workspace;
  }

  private void runSimpleApplicationBundleTestWithBuildTarget(String fqtn)
      throws IOException, InterruptedException {
    runApplicationBundleTestWithScenarioAndBuildTarget("simple_application_bundle_no_debug", fqtn);
  }

  @Test
  public void testDisablingFatBinaryCaching() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_no_debug", tmp);
    workspace.setUp();

    String bundleTarget =
        "//:DemoApp#iphonesimulator-x86_64,iphonesimulator-i386,no-debug,no-include-frameworks";
    String binaryTarget =
        "//:DemoAppBinary#iphonesimulator-x86_64,iphonesimulator-i386,strip-non-global";

    workspace.enableDirCache();
    workspace.runBuckBuild("-c", "cxx.cache_links=false", bundleTarget).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace.runBuckBuild("-c", "cxx.cache_links=false", bundleTarget).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(binaryTarget);
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

  private static void setCommonCatalystBuckConfigLocalOptions(ProjectWorkspace workspace)
      throws IOException {
    workspace.addBuckConfigLocalOption("apple", "target_triple_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "use_entitlements_when_adhoc_code_signing", "true");
    workspace.addBuckConfigLocalOption("cxx", "skip_system_framework_search_paths", "true");
    workspace.addBuckConfigLocalOption("apple", "maccatalyst_target_sdk_version", "13.0");
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");
  }

  @Test
  public void simpleApplicationBundleCatalyst()
      throws IOException, PropertyListFormatException, ParserConfigurationException, SAXException,
          ParseException, InterruptedException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_catalyst", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:CatalystDemoApp#maccatalyst-x86_64,no-debug");
    setCommonCatalystBuckConfigLocalOptions(workspace);
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));

    assertTrue(Files.exists(appPath));

    Path plistPath = appPath.resolve("Contents/Info.plist");
    NSDictionary plist = (NSDictionary) PropertyListParser.parse(plistPath.toString());

    assertNull(plist.get("LSRequiresIPhoneOS"));
    assertNull(plist.get("MinimumOSVersion"));
    assertNotNull(plist.get("LSMinimumSystemVersion"));

    Optional<String> maybeSignatureOutput =
        workspace.runCommand("codesign", "-d", "-v", appPath.toString()).getStderr();
    assertTrue(maybeSignatureOutput.isPresent());
    assertThat(maybeSignatureOutput.get(), not(containsString("code object is not signed at all")));
    assertThat(maybeSignatureOutput.get(), containsString("Signature=adhoc"));
  }

  @Test
  public void simpleApplicationBundleCatalystWithoutCodeSigning()
      throws IOException, InterruptedException {
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSXCATALYST));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_catalyst", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:CatalystDemoApp#maccatalyst-x86_64,no-debug");
    setCommonCatalystBuckConfigLocalOptions(workspace);
    workspace.addBuckConfigLocalOption("apple", "codesign_type_override", "skip");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));

    assertTrue(Files.exists(appPath));

    Optional<String> maybeSignatureOutput =
        workspace.runCommand("codesign", "-d", "-v", appPath.toString()).getStderr();
    assertTrue(maybeSignatureOutput.isPresent());
    assertThat(maybeSignatureOutput.get(), containsString("code object is not signed at all"));
    assertThat(maybeSignatureOutput.get(), not(containsString("Signature=adhoc")));
  }

  @Test
  public void simpleApplicationBundleWithInputBasedRulekey() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_codesigning", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "bundle_input_based_rulekey_enabled", "true");

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
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
  public void applicationBundleWithDefaultPlatform() throws IOException, InterruptedException {
    runApplicationBundleTestWithScenarioAndBuildTarget(
        "default_platform_in_rules", "//:DemoApp#no-debug");
  }

  @Test
  public void applicationBundleWithDefaultPlatformAndFlavor()
      throws IOException, InterruptedException {
    runApplicationBundleTestWithScenarioAndBuildTarget(
        "default_platform_in_rules", "//:DemoApp#iphonesimulator-i386,no-debug");
  }

  @Test
  public void applicationBundleFatBinaryWithDefaultPlatform()
      throws IOException, InterruptedException {
    runApplicationBundleTestWithScenarioAndBuildTarget(
        "default_platform_in_rules",
        "//:DemoApp#iphonesimulator-x86_64,iphonesimulator-i386,no-debug");
  }

  @Test
  public void applicationBundleWithDefaultPlatformIgnoresConfigOverride()
      throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        runApplicationBundleTestWithScenarioAndBuildTarget(
            "default_platform_in_rules", "//:DemoApp#no-debug");
    BuildTarget target = workspace.newBuildTarget("//:DemoApp#no-debug");
    workspace
        .runBuckCommand(
            "build",
            target.getFullyQualifiedName(),
            "--config",
            "cxx.default_platform=doesnotexist")
        .assertSuccess();
  }

  @Test
  public void simpleApplicationBundleWithCodeSigning() throws Exception {
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        runApplicationBundleTestWithScenarioAndBuildTarget(
            "simple_application_bundle_with_codesigning", "//:DemoApp#iphoneos-arm64,no-debug");

    // Do not match iOS profiles on tvOS targets.
    BuildTarget target = workspace.newBuildTarget("//:DemoApp#appletvos-arm64,no-debug");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertFailure();
    assertTrue(result.getStderr().contains("No valid non-expired provisioning profiles match"));

    // Match tvOS profile.
    workspace.addBuckConfigLocalOption(
        "apple", "provisioning_profile_search_path", "provisioning_profiles_tvos");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
  }

  @Test
  public void simpleApplicationBundleWithCodeSigningResources() throws Exception {
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_codesigning", tmp);
    workspace.setUp();

    BuildTarget target =
        workspace.newBuildTarget("//:DemoAppWithAppleResource#iphoneos-arm64,no-debug");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("DemoAppWithAppleResource.app"));
    Path codesignedResourcePath = appPath.resolve("BinaryToBeCodesigned");
    assertTrue(Files.exists(codesignedResourcePath));
    assertTrue(checkCodeSigning(codesignedResourcePath));

    Path nonCodesignedResourcePath = appPath.resolve("OtherBinary");
    assertTrue(Files.exists(nonCodesignedResourcePath));
    assertFalse(checkCodeSigning(nonCodesignedResourcePath));
  }

  @Test
  public void simpleApplicationBundleWithDylibDryRunCodeSigning() throws Exception {
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_codesigning", tmp);
    workspace.addBuckConfigLocalOption("apple", "dry_run_code_signing", "true");
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoAppWithDylib#iphoneos-arm64,no-debug");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("DemoAppWithDylib.app"));

    NSDictionary resultPlist = verifyAndParsePlist(appPath.resolve("BUCK_code_sign_args.plist"));
    assertEquals(new NSArray(new NSString("fake.dylib")), resultPlist.get("extra-paths-to-sign"));
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
    return (NSDictionary) PropertyListParser.parse(resultContents.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void simpleApplicationBundleWithoutDryRunCodeSigning() throws Exception {
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_codesigning", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "dry_run_code_signing", "false");
    workspace.addBuckConfigLocalOption("apple", "codesign_type_override", "distribution");

    BuildTarget target =
        workspace.newBuildTarget("//:DemoAppWithFramework#iphoneos-arm64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    Path codeSignResultsPath = appPath.resolve("BUCK_code_sign_entitlements.plist");
    assertFalse(Files.exists(codeSignResultsPath));
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
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
        RelPath.get("DemoAppWithFramework_output.expected"),
        BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), appTarget, "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), appTarget, "%s")
                .resolve(appTarget.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(appTarget.getShortName())));
    assertTrue(checkCodeSigning(appPath));

    BuildTarget frameworkTarget =
        workspace.newBuildTarget("//:DemoFramework#iphoneos-arm64,no-debug,no-include-frameworks");
    Path frameworkPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), frameworkTarget, "%s")
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
    workspace.assertFilesEqual(
        RelPath.get("DemoApp.xcent.expected"),
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                AppleDescriptions.stripBundleSpecificFlavors(target)
                    .withAppendedFlavors(AppleCodeSignPreparation.FLAVOR),
                "%s")
            .resolveRel("Entitlements.plist"));

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));
  }

  @Test
  public void simpleApplicationBundleForMacCatalystWithProvisioningProfile()
      throws IOException, InterruptedException {
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_mac_catalyst", tmp);
    workspace.addBuckConfigLocalOption("apple", "codesign_type_override", "distribution");
    setCommonCatalystBuckConfigLocalOptions(workspace);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#maccatalyst-x86_64,no-debug");
    ProcessResult result =
        workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    result.assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));

    Path provisioningProfilePath = appPath.resolve("Contents/embedded.provisionprofile");
    assertTrue(Files.exists(provisioningProfilePath));

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
        RelPath.get("DemoAppViaSubstitutions.xcent.expected"),
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                AppleDescriptions.stripBundleSpecificFlavors(target)
                    .withAppendedFlavors(AppleCodeSignPreparation.FLAVOR),
                "%s")
            .resolveRel("Entitlements.plist"));

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));
  }

  @Test
  public void macOsApplicationBundleWithCodeSigningAndEntitlements()
      throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "macos_application_bundle_with_codesigning_and_entitlements", tmp);
    workspace.setUp();
    Path outputPath =
        workspace.buildAndReturnOutput(
            "//:App#macosx-x86_64",
            "--config",
            "apple.use_entitlements_when_adhoc_code_signing=true");

    assertTrue(
        CodeSigning.hasEntitlement(
            new DefaultProcessExecutor(new TestConsole()),
            outputPath,
            "com.apple.security.device.camera"));

    assertTrue(checkCodeSigning(outputPath));
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
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
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
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
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
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
  public void testBundleBinaryHasDsymBundleWithLinkerNormArgs() throws Exception {
    bundleBinaryHasDsymBundleWithLinkerNormArgsState(true);
  }

  @Test
  public void testBundleBinaryHasDsymBundleWithoutLinkerNormArgs() throws Exception {
    bundleBinaryHasDsymBundleWithLinkerNormArgsState(false);
  }

  public void bundleBinaryHasDsymBundleWithLinkerNormArgsState(boolean linkerNormArgs)
      throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_dwarf_and_dsym", tmp);
    workspace.addBuckConfigLocalOption(
        "cxx", "link_path_normalization_args_enabled", linkerNormArgs ? "true" : "false");
    workspace.setUp();

    BuildTarget target =
        workspace.newBuildTarget("//:DemoApp#dwarf-and-dsym,iphonesimulator-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    AbsPath bundlePath =
        AbsPath.of(
            workspace
                .getPath(
                    BuildTargetPaths.getGenPath(
                        filesystem.getBuckPaths(),
                        target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                        "%s"))
                .resolve(target.getShortName() + ".app"));
    AbsPath dwarfPath =
        bundlePath.getParent().resolve("DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp");
    AbsPath binaryPath = bundlePath.resolve("DemoApp");
    assertTrue(Files.exists(dwarfPath.getPath()));
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target
                .withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                .withAppendedFlavors(AppleDebugFormat.DWARF_AND_DSYM.getFlavor()),
            "%s"));

    BuildTarget binaryWithLinkerMap =
        workspace.newBuildTarget("//:DemoAppBinary#iphonesimulator-x86_64");

    RelPath binaryWithLinkerMapPath =
        BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), binaryWithLinkerMap, "%s");
    RelPath linkMapPath =
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(), binaryWithLinkerMap, "%s-LinkMap.txt");

    assertThat(Files.exists(workspace.resolve(binaryWithLinkerMapPath)), equalTo(true));
    assertThat(Files.exists(workspace.resolve(linkMapPath)), equalTo(true));

    BuildTarget binaryWithoutLinkerMap =
        workspace
            .newBuildTarget("//:DemoAppBinary#iphonesimulator-x86_64")
            .withAppendedFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());
    RelPath binaryWithoutLinkerMapPath =
        BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), binaryWithoutLinkerMap, "%s");
    assertThat(Files.exists(workspace.resolve(binaryWithoutLinkerMapPath)), equalTo(false));
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR,
                stripStyle.getFlavor(),
                AppleDebugFormat.NONE.getFlavor()),
            "%s"));

    Path bundlePath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
  }

  @Test
  public void appBundleWithResourcesAsContentDirs() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_resources_as_content_dirs", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
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
        RelPath.get("DemoAppWithPlatformBinary_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
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
            containsString("cc/Localizable.strings' is not.")));
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(
                AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve(target.getShortName()))));
  }

  @Test
  public void appleAssetCatalogsAreIncludedInBundleAndInfoPlistContainsAssetsData()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_asset_catalogs_are_included_in_bundle", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    RelPath outputPath =
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s");
    Path appPath = outputPath.resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Assets.car"))));

    Path plistPath = workspace.getPath(appPath.resolve("Info.plist"));
    InputStream in = new FileInputStream(plistPath.toFile());
    NSObject launchImages =
        AppleInfoPlistParsing.getPropertyValueFromPlistStream(
            Paths.get("Test"), in, "UILaunchImages");
    assertNotNull(launchImages);
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
    workspace.getBuildLog().assertTargetBuiltLocally(genruleTarget);

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
    RelPath outputPath =
        BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, genPathFormat);
    Path path = workspace.getPath(outputPath);
    assertTrue(Files.exists(path));
    String contents = workspace.getFileContents(outputPath);
    assertTrue(contents.contains(needle));
  }

  @Test
  public void appleAssetCatalogsWithMoreThanOneAppIconOrLaunchImageShouldFail() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_asset_catalogs_are_included_in_bundle", tmp);
    workspace.setUp();
    BuildTarget target =
        BuildTargetFactory.newInstance("//:DemoAppWithMoreThanOneIconAndLaunchImage#no-debug");
    ProcessResult processResult = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString("At most one asset catalog in the dependencies of"));
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

    ImmutableSet<String> targetsThatMightContainIncludeFrameworkFlavors =
        ImmutableSet.of("//:DemoAppWithExtension", "//:DemoExtension");

    ImmutableSet<Flavor> includeFrameworkFlavors =
        AppleDescriptions.INCLUDE_FRAMEWORKS.getFlavors();
    ImmutableSet<Flavor>
        expectedInternalFlavorsForSameBaseNameAsBundleExclusiveToIncludeFrameworkFlavors =
            ImmutableSet.of(
                AppleInfoPlist.FLAVOR, ApplePkgInfo.FLAVOR, AppleProcessResources.FLAVOR);

    for (BuildTarget builtTarget : buckBuildLog.getAllTargets()) {
      ImmutableSet<Flavor> allFlavors = builtTarget.getFlavors().getSet();
      if (!Sets.intersection(
              allFlavors,
              expectedInternalFlavorsForSameBaseNameAsBundleExclusiveToIncludeFrameworkFlavors)
          .isEmpty()) {
        assertTrue(Sets.intersection(allFlavors, includeFrameworkFlavors).isEmpty());
      } else if (Sets.intersection(allFlavors, includeFrameworkFlavors).isEmpty()) {
        assertThat(
            builtTarget.getUnflavoredBuildTarget().getFullyQualifiedName(),
            not(in(targetsThatMightContainIncludeFrameworkFlavors)));
      } else {
        assertThat(
            builtTarget.getUnflavoredBuildTarget().getFullyQualifiedName(),
            in(targetsThatMightContainIncludeFrameworkFlavors));
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
    workspace.assertFilesEqual(
        RelPath.get("DemoApp.xcent.expected"),
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                AppleDescriptions.stripBundleSpecificFlavors(target)
                    .withAppendedFlavors(AppleCodeSignPreparation.FLAVOR),
                "%s")
            .resolveRel("Entitlements.plist"));

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("AppViewController.nib"))));
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Model.momd"))));
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Model2.momd"))));
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("DemoApp.scnassets"))));
  }

  @Test
  public void resourcesAreCompiledGivenProcessingHappensInSeparateRule() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_compiled_resources", tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));

    String resourceName = "Resource.plist";
    assertFalse(
        "The resource should be absent in the app bundle.",
        Files.exists(appPath.resolve(resourceName)));

    Path frameworkPath = appPath.resolve("Frameworks/TestFramework.framework");
    assertTrue(
        "The resource should be present in the embedded framework.",
        Files.exists(frameworkPath.resolve(resourceName)));
  }

  @Test
  public void resourceGroupDoesNotDuplicateResourcesInAppAndFramework() throws Exception {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_embedded_framework_and_resource_groups", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "link_groups_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "codesign", "/usr/bin/true");

    BuildTarget target = BuildTargetFactory.newInstance("//:App#no-debug,macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(
                        AppleDebugFormat.NONE.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));

    String appResourceName = "resource_app.txt";
    String utilityResourceName = "resource_utility.txt";
    String frameworkResourceName = "resource_framework.txt";

    Path appBundleResourcesPath = appPath.resolve("Contents/Resources");
    assertTrue(
        "App resource should be present in the app bundle.",
        Files.exists(appBundleResourcesPath.resolve(appResourceName)));
    assertTrue(
        "Utility resource should be present in the app bundle.",
        Files.exists(appBundleResourcesPath.resolve(utilityResourceName)));
    assertFalse(
        "Framework resource should be absent in the app bundle.",
        Files.exists(appBundleResourcesPath.resolve(frameworkResourceName)));

    Path frameworkResourcesPath =
        appPath.resolve("Contents/Frameworks/AppFramework.framework/Resources");
    assertFalse(
        "App resource should be absent the framework bundle.",
        Files.exists(frameworkResourcesPath.resolve(appResourceName)));
    assertTrue(
        "Utility resource should be present in the framework bundle.",
        Files.exists(frameworkResourcesPath.resolve(utilityResourceName)));
    assertTrue(
        "Framework resource should be present in the framework bundle.",
        Files.exists(frameworkResourcesPath.resolve(frameworkResourceName)));
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
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertThat(result.getStdout(), startsWith(target.getFullyQualifiedName() + " " + appPath));

    // test debug output
    target = BuildTargetFactory.newInstance("//:DemoApp#dwarf-and-dsym");
    result = workspace.runBuckCommand("targets", "--show-output", target.getFullyQualifiedName());
    result.assertSuccess();
    appPath =
        BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                "%s")
            .resolve(target.getShortName() + ".app");
    assertThat(result.getStdout(), startsWith(target.getFullyQualifiedName() + " " + appPath));
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
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
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
  public void resourcesWithFrameworksDestinationsAreProperlyCopiedOnIosPlatform()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "bundle_with_resource_with_frameworks_destination", tmp);
    workspace.setUp();
    Path outputPath = workspace.buildAndReturnOutput("//:bundle#iphonesimulator-x86_64");
    assertTrue(
        "Resource file should exist in Frameworks directory.",
        Files.isRegularFile(outputPath.resolve("Frameworks/file.txt")));
  }

  @Test
  public void resourcesWithFrameworksDestinationsAreProperlyCopiedOnMacosxPlatform()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "bundle_with_resource_with_frameworks_destination", tmp);
    workspace.setUp();
    Path outputPath =
        workspace.buildAndReturnOutput(
            "//:bundle#macosx-x86_64", "--config", "apple.codesign=/usr/bin/true");
    assertTrue(
        "Resource file should exist in Frameworks directory.",
        Files.isRegularFile(outputPath.resolve("Contents/Frameworks/file.txt")));
  }

  @Test
  public void resourcesWithExecutablesDestinationsAreProperlyCopiedOnIosPlatform()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "bundle_with_resource_with_executables_destination", tmp);
    workspace.setUp();
    Path outputPath = workspace.buildAndReturnOutput("//:bundle#iphonesimulator-x86_64");
    assertTrue(
        "Resource file should exist in Executables directory.",
        Files.isRegularFile(outputPath.resolve("file.txt")));
  }

  @Test
  public void resourcesWithExecutablesDestinationsAreProperlyCopiedOnMacosxPlatform()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "bundle_with_resource_with_executables_destination", tmp);
    workspace.setUp();
    Path outputPath =
        workspace.buildAndReturnOutput(
            "//:bundle#macosx-x86_64", "--config", "apple.codesign=/usr/bin/true");
    assertTrue(
        "Resource file should exist in Executables directory.",
        Files.isRegularFile(outputPath.resolve("Contents/MacOS/file.txt")));
  }

  @Test
  public void resourcesWithResourcesDestinationsAreProperlyCopiedOnIosPlatform()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "bundle_with_resource_with_resources_destination", tmp);
    workspace.setUp();
    Path outputPath = workspace.buildAndReturnOutput("//:bundle#iphonesimulator-x86_64");
    assertTrue(
        "Resource file should exist in Resources directory.",
        Files.isRegularFile(outputPath.resolve("file.txt")));
  }

  @Test
  public void resourcesWithResourcesDestinationsAreProperlyCopiedOnMacosxPlatform()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "bundle_with_resource_with_resources_destination", tmp);
    workspace.setUp();
    Path outputPath =
        workspace.buildAndReturnOutput(
            "//:bundle#macosx-x86_64", "--config", "apple.codesign=/usr/bin/true");
    assertTrue(
        "Resource file should exist in Resources directory.",
        Files.isRegularFile(outputPath.resolve("Contents/Resources/file.txt")));
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
    ProcessResult processResult =
        workspace.runBuckBuild("//:bundle_without_binary#iphonesimulator-x86_64");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "Binary matching target platform iphonesimulator-x86_64 cannot be found"
                + " and binary default is not specified."));
  }

  @Test
  public void errorMessageForBundleWithMultipleMatchingBinariesIsDisplayed() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_platform_binary", tmp);
    workspace.setUp();
    ProcessResult processResult =
        workspace.runBuckBuild("//:bundle_with_multiple_matching_binaries#iphonesimulator-x86_64");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "There must be at most one binary matching the target platform "
                + "iphonesimulator-x86_64 but all of [//:binary, //:binary] matched. "
                + "Please make your pattern more precise and remove any duplicates."));
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
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));

    Path appPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve(target.getShortName() + ".app"));
    assertTrue(Files.exists(appPath.resolve(target.getShortName())));

    assertTrue(checkCodeSigning(appPath));

    // Non-Swift target shouldn't include Frameworks/
    assertFalse(Files.exists(appPath.resolve("Frameworks")));
  }

  @Test
  public void coreDataModelIsIncludedInBundle() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_core_data_model_is_included_in_bundle", tmp);
    workspace.setUp();
    BuildTarget target = BuildTargetFactory.newInstance("//:DemoApp#no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    RelPath outputPath =
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s");
    Path appPath = outputPath.resolve(target.getShortName() + ".app");
    assertTrue(Files.exists(workspace.getPath(appPath.resolve("Model.momd"))));
  }

  @Test
  public void testCustomTargetSDKVersionOnMobile()
      throws IOException, ParserConfigurationException, ParseException, SAXException,
          PropertyListFormatException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_target_sdk", tmp);
    workspace.setUp();

    BuildTarget bundleTarget =
        BuildTargetFactory.newInstance(
            "//:DemoApp#iphonesimulator-x86_64,no-debug,no-include-frameworks");
    Path bundlePath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), bundleTarget, "%s/DemoApp.app"));
    workspace.runBuckBuild(bundleTarget.toString()).assertSuccess();

    Path plistPath = bundlePath.resolve("Info.plist");
    NSDictionary plist = (NSDictionary) PropertyListParser.parse(plistPath.toFile());
    NSString minVersion = (NSString) plist.get("MinimumOSVersion");

    // The apple_binary() has a custom target_sdk_version set to 12.1, so we expect to see it
    // in the Info.plist.
    assertThat(minVersion.toString(), equalTo("12.1"));
  }

  @Test
  public void testCustomTargetSDKVersionOnDesktop()
      throws IOException, ParserConfigurationException, ParseException, SAXException,
          PropertyListFormatException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_application_bundle_with_target_sdk", tmp);
    workspace.addBuckConfigLocalOption("apple", "codesign", "/usr/bin/true");
    workspace.setUp();

    BuildTarget bundleTarget =
        BuildTargetFactory.newInstance(
            "//:DemoMacApp#macosx-x86_64,no-debug,no-include-frameworks");
    Path bundlePath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), bundleTarget, "%s/DemoMacApp.app"));
    workspace.runBuckBuild(bundleTarget.toString()).assertSuccess();

    Path plistPath = bundlePath.resolve("Contents/Info.plist");
    NSDictionary plist = (NSDictionary) PropertyListParser.parse(plistPath.toFile());
    NSString minVersion = (NSString) plist.get("LSMinimumSystemVersion");

    // The apple_binary() has a custom target_sdk_version set to 10.14, so we expect to see it
    // in the Info.plist.
    assertThat(minVersion.toString(), equalTo("10.14"));
  }

  @Test
  public void resourcesHashesAreComputed() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "app_bundle_with_resources", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.addBuckConfigLocalOption("apple", "incremental_bundling_enabled", "true");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    {
      // Non-processed resources

      Path path =
          workspace.getPath(
              BuildTargetPaths.getGenPath(
                      filesystem.getBuckPaths(),
                      AppleDescriptions.stripBundleSpecificFlavors(target)
                          .withAppendedFlavors(
                              AppleComputeNonProcessedResourcesContentHashes.FLAVOR),
                      "%s")
                  .resolveRel("non-processed-resources-hashes.json"));

      assertTrue(filesystem.exists(path));

      JsonParser parser = ObjectMappers.createParser(path);
      Map<String, String> pathToHash =
          parser.readValueAs(new TypeReference<TreeMap<String, String>>() {});

      assertEquals(2, pathToHash.size());

      {
        StringBuilder hashBuilder = new StringBuilder();
        Step step =
            new AppleComputeFileOrDirectoryHashStep(
                hashBuilder, AbsPath.of(workspace.getPath("Image.png")), filesystem, true, true);
        step.execute(TestExecutionContext.newInstance());
        assertEquals(hashBuilder.toString(), pathToHash.get("Image.png"));
      }

      {
        StringBuilder hashBuilder = new StringBuilder();
        Step step =
            new AppleComputeFileOrDirectoryHashStep(
                hashBuilder, AbsPath.of(workspace.getPath("Images")), filesystem, true, true);
        step.execute(TestExecutionContext.newInstance());
        assertEquals(hashBuilder.toString(), pathToHash.get("Images"));
      }
    }

    {
      // Processed resources

      Path targetDirectoryPath =
          workspace.getPath(
              BuildTargetPaths.getGenPath(
                  filesystem.getBuckPaths(),
                  AppleDescriptions.stripBundleSpecificFlavors(target)
                      .withAppendedFlavors(AppleProcessResources.FLAVOR),
                  "%s"));

      Path hashesFilePath = targetDirectoryPath.resolve("content_hashes.json");

      assertTrue(filesystem.exists(hashesFilePath));

      JsonParser parser = ObjectMappers.createParser(hashesFilePath);
      Map<String, String> pathToHash =
          parser.readValueAs(new TypeReference<TreeMap<String, String>>() {});

      assertEquals(6, pathToHash.size());

      for (String name :
          ImmutableList.of(
              "aa.lproj", "bb.lproj", "cc.lproj", "xx.lproj", "yy.lproj", "zz.lproj")) {
        StringBuilder hashBuilder = new StringBuilder();

        Path processedFilePath =
            targetDirectoryPath
                .resolve("BundleParts")
                .resolve(
                    AppleProcessResources.directoryNameWithProcessedFilesForDestination(
                            AppleBundleDestination.RESOURCES)
                        .getPath())
                .resolve(name);

        Step step =
            new AppleComputeFileOrDirectoryHashStep(
                hashBuilder, AbsPath.of(processedFilePath), filesystem, true, true);
        step.execute(TestExecutionContext.newInstance());
        assertEquals(hashBuilder.toString(), pathToHash.get(name));
      }
    }
  }

  @Test
  public void givenPlatformIsMacOsBundlePartsHashesAreWrittenToDisk()
      throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_parts_of_every_kind", tmp);
    workspace.setUp();

    Path buildTriggerPath = Paths.get("App/BuildTrigger.m");
    filesystem.writeContentsToPath("", buildTriggerPath);

    workspace.addBuckConfigLocalOption("apple", "incremental_bundling_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "codesign", "/usr/bin/true");

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#macosx-x86_64,no-debug");
    Path outputPath = workspace.buildAndReturnOutput(target.toString());
    Path incrementalInfoFilePath = outputPath.resolve("../incremental_info.json");
    assertTrue("File with hashes should exist", Files.isRegularFile(incrementalInfoFilePath));

    JsonParser parser = ObjectMappers.createParser(incrementalInfoFilePath);
    AppleBundleIncrementalInfo incrementalInfo =
        parser.readValueAs(AppleBundleIncrementalInfo.class);
    Map<RelPath, String> pathToHash = incrementalInfo.getHashes();

    ImmutableMap.Builder<RelPath, String> expectedBuilder = ImmutableMap.builder();
    for (String path :
        ImmutableList.of(
            "Contents/Frameworks/TestFramework.framework/TestFramework",
            "Contents/Frameworks/TestFramework.framework/Resources/Info.plist",
            "Contents/Frameworks/TestFramework.framework/Resources/PkgInfo",
            "Contents/MacOS/DemoApp",
            "Contents/MacOS/Worker",
            "Contents/PkgInfo",
            "Contents/Resources/DemoApp.scnassets",
            "Contents/Resources/Image.png",
            "Contents/Resources/Images",
            "Contents/Resources/Model.momd",
            "Contents/Resources/aa.lproj",
            "Contents/Resources/xx.lproj")) {

      StringBuilder hashBuilder = new StringBuilder();
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder, AbsPath.of(outputPath.resolve(path)), filesystem, true, true);
      step.execute(TestExecutionContext.newInstance());
      expectedBuilder.put(RelPath.get(path), hashBuilder.toString());
    }

    {
      Path infoPlistOutputDirectoryPath =
          workspace.getPath(
              BuildTargetPaths.getGenPath(
                  filesystem.getBuckPaths(),
                  AppleDescriptions.stripBundleSpecificFlavors(target)
                      .withAppendedFlavors(AppleInfoPlist.FLAVOR),
                  "%s"));

      Path processedFilePath = infoPlistOutputDirectoryPath.resolve("Info.plist");

      StringBuilder hashBuilder = new StringBuilder();
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(outputPath.resolve(processedFilePath)),
              filesystem,
              true,
              true);
      step.execute(TestExecutionContext.newInstance());
      expectedBuilder.put(RelPath.get("Contents/Info.plist"), hashBuilder.toString());
    }

    assertEquals(expectedBuilder.build(), pathToHash);
  }

  @Test
  public void givenPlatformIsIosBundlePartsHashesAreWrittenToDisk()
      throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_parts_of_every_kind", tmp);
    workspace.setUp();

    Path buildTriggerPath = Paths.get("App/BuildTrigger.m");
    filesystem.writeContentsToPath("", buildTriggerPath);

    workspace.addBuckConfigLocalOption("apple", "incremental_bundling_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "codesign", "/usr/bin/true");

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    Path outputPath = workspace.buildAndReturnOutput(target.toString());
    Path incrementalInfoFilePath = outputPath.resolve("../incremental_info.json");
    assertTrue("File with hashes should exist", Files.isRegularFile(incrementalInfoFilePath));

    JsonParser parser = ObjectMappers.createParser(incrementalInfoFilePath);
    AppleBundleIncrementalInfo incrementalInfo =
        parser.readValueAs(AppleBundleIncrementalInfo.class);
    Map<RelPath, String> pathToHash = incrementalInfo.getHashes();

    ImmutableMap.Builder<RelPath, String> expectedBuilder = ImmutableMap.builder();
    for (String path :
        ImmutableList.of(
            "Frameworks/TestFramework.framework/Info.plist",
            "Frameworks/TestFramework.framework/PkgInfo",
            "Frameworks/TestFramework.framework/TestFramework",
            "DemoApp",
            "PkgInfo",
            "DemoApp.scnassets",
            "Image.png",
            "Images",
            "Model.momd",
            "aa.lproj",
            "xx.lproj")) {

      StringBuilder hashBuilder = new StringBuilder();
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder, AbsPath.of(outputPath.resolve(path)), filesystem, true, true);
      step.execute(TestExecutionContext.newInstance());
      expectedBuilder.put(RelPath.get(path), hashBuilder.toString());
    }

    {
      Path infoPlistOutputDirectoryPath =
          workspace.getPath(
              BuildTargetPaths.getGenPath(
                  filesystem.getBuckPaths(),
                  AppleDescriptions.stripBundleSpecificFlavors(target)
                      .withAppendedFlavors(AppleInfoPlist.FLAVOR),
                  "%s"));

      Path processedFilePath = infoPlistOutputDirectoryPath.resolve("Info.plist");

      StringBuilder hashBuilder = new StringBuilder();
      Step step =
          new AppleComputeFileOrDirectoryHashStep(
              hashBuilder,
              AbsPath.of(outputPath.resolve(processedFilePath)),
              filesystem,
              true,
              true);
      step.execute(TestExecutionContext.newInstance());
      expectedBuilder.put(RelPath.get("Info.plist"), hashBuilder.toString());
    }

    assertEquals(expectedBuilder.build(), pathToHash);
  }

  @Test
  public void
      givenBundlePartIsChanged_whenIncrementalBuildIsPerformed_thenOnlyChangedPartIsCopiedToBundle()
          throws IOException, InterruptedException {
    // directories with contents
    givenBundleElementIsChanged_whenIncrementalBuildIsPerformed_thenOnlyChangedElementIsCopiedToBundle(
        RelPath.get("Contents/Resources/Model.momd"),
        RelPath.get("Contents/Resources/DemoApp.scnassets"));
    // processed resources
    givenBundleElementIsChanged_whenIncrementalBuildIsPerformed_thenOnlyChangedElementIsCopiedToBundle(
        RelPath.get("Contents/Resources/aa.lproj"), RelPath.get("Contents/Resources/xx.lproj"));
    // non-processed parts
    givenBundleElementIsChanged_whenIncrementalBuildIsPerformed_thenOnlyChangedElementIsCopiedToBundle(
        RelPath.get("Contents/Resources/Image.png"), RelPath.get("Contents/PkgInfo"));
  }

  private void
      givenBundleElementIsChanged_whenIncrementalBuildIsPerformed_thenOnlyChangedElementIsCopiedToBundle(
          RelPath path1, RelPath path2) throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_parts_of_every_kind", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("apple", "incremental_bundling_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "codesign", "/usr/bin/true");

    Path buildTriggerPath = Paths.get("App/BuildTrigger.m");
    filesystem.writeContentsToPath("", buildTriggerPath);

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#macosx-x86_64,no-debug");
    Path outputPath = workspace.buildAndReturnOutput(target.toString());
    Path incrementalInfoFilePath = outputPath.resolve("../incremental_info.json");

    assertTrue("First file should exist", Files.exists(outputPath.resolve(path1.getPath())));
    assertTrue("Second file should exist", Files.exists(outputPath.resolve(path2.getPath())));

    JsonParser parser = ObjectMappers.createParser(incrementalInfoFilePath);
    AppleBundleIncrementalInfo incrementalInfo =
        parser.readValueAs(AppleBundleIncrementalInfo.class);
    Map<RelPath, String> pathToHash = incrementalInfo.getHashes();

    RelPath shouldNotBeCopiedPath = path1;
    RelPath shouldBeCopiedPath = path2;
    pathToHash.put(shouldBeCopiedPath, "");

    filesystem.deleteFileAtPath(incrementalInfoFilePath);
    filesystem.deleteRecursivelyIfExists(outputPath.resolve(shouldBeCopiedPath.getPath()));
    filesystem.deleteRecursivelyIfExists(outputPath.resolve(shouldNotBeCopiedPath.getPath()));

    (new AppleWriteIncrementalInfoStep(
            () -> ImmutableMap.copyOf(pathToHash),
            incrementalInfo.getCodeSignedOnCopyPaths(),
            incrementalInfo.codeSigned(),
            incrementalInfoFilePath,
            filesystem))
        .execute(TestExecutionContext.newInstance());

    filesystem.deleteFileAtPath(buildTriggerPath);
    filesystem.writeContentsToPath("int i = 1;", buildTriggerPath);

    workspace.buildAndReturnOutput(target.toString());

    assertTrue(filesystem.exists(outputPath.resolve(shouldBeCopiedPath.getPath())));
    assertFalse(filesystem.exists(outputPath.resolve(shouldNotBeCopiedPath.getPath())));
  }

  @Test
  public void
      givenFileIsRemovedFromProjectAfterSuccessfulBuild_whenIncrementalBuildIsPerformed_thenItIsRemovedFromNewVersionOfBundle()
          throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_parts_of_every_kind", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("apple", "incremental_bundling_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "codesign", "/usr/bin/true");

    Path buildTriggerPath = Paths.get("App/BuildTrigger.m");
    filesystem.writeContentsToPath("", buildTriggerPath);

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#macosx-x86_64,no-debug");
    Path outputPath = workspace.buildAndReturnOutput(target.toString());

    assertTrue(filesystem.exists(outputPath.resolve("Contents/Resources/xx.lproj")));

    Path buckFilePath = Paths.get("BUCK");

    List<String> lines = filesystem.readLines(buckFilePath);
    List<String> updatedLines =
        lines.stream()
            .filter(s -> !s.contains("Strings/xx.lproj/Localizable.strings"))
            .collect(Collectors.toList());
    filesystem.writeLinesToPath(updatedLines, buckFilePath);

    workspace.buildAndReturnOutput(target.toString());

    assertFalse(filesystem.exists(outputPath.resolve("Contents/Resources/xx.lproj")));
  }

  @Test
  public void
      givenPreviousBuildIsIncremental_whenNonIncrementalBuildIsPerformed_thenFileWithContentHashesIsRemoved()
          throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_parts_of_every_kind", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("apple", "incremental_bundling_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "codesign", "/usr/bin/true");

    Path buildTriggerPath = Paths.get("App/BuildTrigger.m");
    filesystem.writeContentsToPath("", buildTriggerPath);

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#macosx-x86_64,no-debug");
    Path outputPath = workspace.buildAndReturnOutput(target.toString());

    Path incrementalInfoFilePath = outputPath.resolve("../incremental_info.json");
    assertTrue("File with hashes should exist", Files.isRegularFile(incrementalInfoFilePath));

    workspace.removeBuckConfigLocalOption("apple", "incremental_bundling_enabled");

    workspace.buildAndReturnOutput(target.toString());

    assertFalse(
        "File with hashes should not exist after non-incremental build",
        Files.exists(incrementalInfoFilePath));
  }

  @Test
  public void
      givenFrameworkIsRemovedFromDependenciesInCurrentBuild_whenIncrementalBuildIsPerformed_thenEmptyFrameworkDirectoryIsRemovedFromBundle()
          throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_parts_of_every_kind", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("apple", "incremental_bundling_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "codesign_type_override", "skip");

    Path buildTriggerPath = Paths.get("App/BuildTrigger.m");
    filesystem.writeContentsToPath("", buildTriggerPath);

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#macosx-x86_64,no-debug");
    workspace.buildAndReturnOutput(target.toString());

    Path buckFilePath = Paths.get("BUCK");

    List<String> lines = filesystem.readLines(buckFilePath);
    List<String> updatedLines =
        lines.stream()
            // Hacky way to get rid of :TestFramework dependency by duplicating :Worker dependency
            .map(s -> s.replaceAll(":TestFramework", ":Worker"))
            .collect(Collectors.toList());
    filesystem.writeLinesToPath(updatedLines, buckFilePath);

    Path outputPath = workspace.buildAndReturnOutput(target.toString());

    assertFalse(Files.exists(outputPath.resolve("Contents/Frameworks")));
  }

  @Test
  public void
      givenFrameworkIsAddedToDependenciesInCurrentBuild_whenIncrementalBuildIsPerformed_thenFrameworkDirectoryIsCorrectlyAddedToBundle()
          throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_parts_of_every_kind", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("apple", "incremental_bundling_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "codesign_type_override", "skip");

    Path buildTriggerPath = Paths.get("App/BuildTrigger.m");
    filesystem.writeContentsToPath("", buildTriggerPath);

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#macosx-x86_64,no-debug");
    workspace.buildAndReturnOutput(target.toString());

    Path buckFilePath = Paths.get("BUCK");

    List<String> lines = filesystem.readLines(buckFilePath);
    List<String> updatedLines =
        lines.stream()
            // Hacky way to add :TestFramework2 dependency
            .map(s -> s.replaceAll(":TestFramework", ":TestFramework\", \":TestFramework2"))
            .collect(Collectors.toList());
    filesystem.writeLinesToPath(updatedLines, buckFilePath);

    Path outputPath = workspace.buildAndReturnOutput(target.toString());

    assertTrue(Files.exists(outputPath.resolve("Contents/Frameworks/TestFramework2.framework")));
    assertTrue(
        Files.exists(
            outputPath.resolve(
                "Contents/Frameworks/TestFramework2.framework/Resources/Info.plist")));
    assertTrue(
        Files.exists(
            outputPath.resolve("Contents/Frameworks/TestFramework2.framework/Resources/PkgInfo")));
    assertTrue(
        Files.exists(
            outputPath.resolve("Contents/Frameworks/TestFramework2.framework/TestFramework2")));
  }

  @Test
  public void resourcesHashesAreNotComputedByDefault() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "app_bundle_with_resources", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    {
      // Non-processed resources
      Path path =
          workspace.getPath(
              BuildTargetPaths.getGenPath(
                  filesystem.getBuckPaths(),
                  AppleDescriptions.stripBundleSpecificFlavors(target)
                      .withAppendedFlavors(AppleComputeNonProcessedResourcesContentHashes.FLAVOR),
                  "%s"));
      assertFalse(filesystem.exists(path));
    }
    {
      // Processed resources
      Path path =
          workspace.getPath(
              BuildTargetPaths.getGenPath(
                      filesystem.getBuckPaths(),
                      AppleDescriptions.stripBundleSpecificFlavors(target)
                          .withAppendedFlavors(AppleProcessResources.FLAVOR),
                      "%s")
                  .resolve("incremental_info.json"));
      assertFalse(filesystem.exists(path));
    }
  }

  @Test
  public void givenBundleContainsDuplicateResourcesWithSameContentThenBuildSucceeds()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_duplicated_resources", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("apple", "incremental_bundling_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "codesign_type_override", "skip");

    BuildTarget target =
        workspace.newBuildTarget("//:DemoAppWithDuplicatedResources#macosx-x86_64,no-debug");
    workspace.buildAndReturnOutput(target.toString());
  }

  @Test
  public void givenBundleContainsDuplicateResourcesWithDifferentContentThenBuildFails()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_duplicated_resources", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("apple", "incremental_bundling_enabled", "true");
    workspace.addBuckConfigLocalOption("apple", "codesign_type_override", "skip");

    BuildTarget target =
        workspace.newBuildTarget(
            "//:DemoAppWithDifferentDuplicatedResources#macosx-x86_64,no-debug");
    workspace.runBuckBuild(target.toString()).assertFailure();
  }

  @Test
  public void appBundleWithCxxResources() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "app_bundle_with_cxx_resources", tmp);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:DemoApp#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    workspace.verify(
        RelPath.get("DemoApp_output.expected"),
        BuildTargetPaths.getGenPath(
            filesystem.getBuckPaths(),
            target.withAppendedFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            "%s"));
  }
}
