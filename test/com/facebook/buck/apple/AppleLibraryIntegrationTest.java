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
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxDiagnosticsEnhancer;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.toolchain.HeaderMode;
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
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class AppleLibraryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testAppleLibraryDiagnostic() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_library_diagnostics", tmp);
    workspace.addBuckConfigLocalOption("cxx", "pch_enabled", "false");
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#diagnostics,macosx-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path diagnosticsPath =
        filesystem.getPathForRelativePath(
            BuildPaths.getGenDir(filesystem.getBuckPaths(), target)
                .resolve(CxxDiagnosticsEnhancer.DIAGNOSTICS_JSON_FILENAME));

    assertTrue(Files.exists(diagnosticsPath));

    JsonNode diagnosticsArray =
        ObjectMappers.READER.readTree(new String(Files.readAllBytes(diagnosticsPath)));
    assertTrue(diagnosticsArray.isArray());
    assertSame(
        diagnosticsArray.size(),
        /** number of source files */
        2);

    for (int i = 0; i < diagnosticsArray.size(); ++i) {
      JsonNode fileDiagnosticsInfo = diagnosticsArray.get(i);
      assertTrue(fileDiagnosticsInfo.isObject());

      JsonNode inputPathNode = fileDiagnosticsInfo.get("input_path");
      assertTrue(inputPathNode.isTextual());
      Path inputPath = Paths.get(inputPathNode.textValue());
      assertFalse(inputPath.isAbsolute());

      JsonNode diagnosticName = fileDiagnosticsInfo.get("diagnostic_name");
      assertTrue(diagnosticName.isTextual());
      assertEquals(diagnosticName.textValue(), "tc");

      JsonNode tokenCountDiagnostics = fileDiagnosticsInfo.get("diagnostic_data");
      assertTrue(tokenCountDiagnostics.isObject());

      JsonNode tokenCount = tokenCountDiagnostics.get("token_count");
      assertTrue(tokenCount.isNumber());
      assertTrue(tokenCount.numberValue().longValue() > 0);

      JsonNode compilerArgs = tokenCountDiagnostics.get("compiler_args");
      assertTrue(jsonArrayContainsString(compilerArgs, "Libraries/TestLibrary/Common.h"));
    }
  }

  private static boolean jsonArrayContainsString(JsonNode node, String string) {
    Preconditions.checkArgument(node.isArray());

    for (int i = 0; i < node.size(); ++i) {
      JsonNode element = node.get(i);
      if (element.isTextual() && element.textValue().equals(string)) {
        return true;
      }
    }

    return false;
  }

  @Test
  public void testAppleLibraryBuildsSomething() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#static,default");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"))));
  }

  @Test
  public void testAppleLibraryHeadersOnlyWithoutSwift() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_library_headers_only", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#shared,iphonesimulator-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"))));
  }

  @Test
  public void testAppleLibraryHeadersOnlyWithSwift() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_library_headers_only", tmp);
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestSwiftLibrary#shared,iphonesimulator-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"))));
  }

  @Test
  public void appleLibraryUsesPlatformDepOfSpecifiedPlatform() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_platform_deps", tmp);
    workspace.setUp();

    // arm64 platform dependency works, so the build should succeed
    workspace
        .runBuckCommand(
            "build",
            BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#iphoneos-arm64")
                .getFullyQualifiedName())
        .assertSuccess();

    // armv7 platform dependency is broken, so the build should fail
    workspace
        .runBuckCommand(
            "build",
            BuildTargetFactory.newInstance("//Apps/TestApp:TestApp#iphoneos-armv7")
                .getFullyQualifiedName())
        .assertFailure();
  }

  @Test
  public void testAppleLibraryWithHeaderPathPrefix() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_header_path_prefix", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#static,default");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"))));
  }

  @Test
  public void testCanUseAHeaderWithoutPrefix() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_header_path_prefix", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary2:TestLibrary2#static,default");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"))));
  }

  @Test
  public void testAppleLibraryWithDefaultsInConfigBuildsSomething() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    workspace.addBuckConfigLocalOption(
        "defaults.apple_library", "platform", "iphonesimulator-x86_64");
    workspace.addBuckConfigLocalOption("defaults.apple_library", "type", "shared");

    BuildTarget target = BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    BuildTarget implicitTarget =
        target.withAppendedFlavors(
            InternalFlavor.of("shared"), InternalFlavor.of("iphonesimulator-x86_64"));
    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), implicitTarget, "%s"))));
  }

  @Test
  public void testAppleLibraryWithDefaultsInRuleBuildsSomething() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_platform_and_type", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target = BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    BuildTarget implicitTarget =
        target.withAppendedFlavors(
            InternalFlavor.of("shared"), InternalFlavor.of("iphoneos-arm64"));
    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), implicitTarget, "%s"));
    assertTrue(Files.exists(outputPath));
  }

  @Test
  public void testAppleLibraryBuildsForWatchOS() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    ImmutableList<String> platforms =
        ImmutableList.of("watchos-armv7k", "watchos-arm64_32", "watchos-armv7k,watchos-arm64_32");
    for (String platform : platforms) {
      BuildTarget target =
          BuildTargetFactory.newInstance(
              "//Libraries/TestLibrary:TestLibrary#" + platform + ",static");
      ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
      result.assertSuccess();

      assertTrue(
          Files.exists(
              workspace.getPath(
                  BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"))));
    }
  }

  @Test
  public void testAppleLibraryBuildsForWatchSimulator() throws IOException {
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHSIMULATOR));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    ImmutableList<String> platforms =
        ImmutableList.of(
            "watchsimulator-i386",
            "watchsimulator-x86_64",
            "watchsimulator-i386,watchsimulator-x86_64");
    for (String platform : platforms) {
      BuildTarget target =
          BuildTargetFactory.newInstance(
              "//Libraries/TestLibrary:TestLibrary#" + platform + ",static");
      ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
      result.assertSuccess();

      assertTrue(
          Files.exists(
              workspace.getPath(
                  BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"))));
    }
  }

  @Test
  public void testAppleLibraryBuildsForAppleTVOS() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.APPLETVOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#appletvos-arm64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"))));
  }

  @Test
  public void testAppleLibraryBuildsForAppleTVSimulator() throws IOException {
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.APPLETVSIMULATOR));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#appletvsimulator-x86_64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"))));
  }

  @Test
  public void testBuildAppleLibraryThatHasSwiftBuildsForAppleTVOS() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.APPLETVOS));

    UserFlavor tvosFlavor = UserFlavor.of("appletvos-arm64", "buck boilerplate");
    testBuildAppleLibraryThatHasSwiftWithLocalConfig(tvosFlavor, ImmutableMap.of());
  }

  @Test
  public void testAppleLibraryBuildsSomethingUsingAppleCxxPlatform() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#static,macosx-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(
        Files.exists(
            workspace.getPath(
                BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"))));
  }

  @Test
  public void testAppleLibraryHeaderSymlinkTree() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_header_symlink_tree", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#"
                + "default,"
                + CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR);
    ProcessResult result = workspace.runBuckCommand("build", buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path inputPath =
        workspace
            .getPath(
                buildTarget.getCellRelativeBasePath().getPath().toPath(filesystem.getFileSystem()))
            .toRealPath();
    Path outputPath =
        workspace
            .getPath(BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s"))
            .toRealPath();

    assertIsSymbolicLink(
        outputPath.resolve("PrivateHeader.h"), inputPath.resolve("PrivateHeader.h"));
    assertIsSymbolicLink(
        outputPath.resolve("TestLibrary/PrivateHeader.h"), inputPath.resolve("PrivateHeader.h"));
    assertIsSymbolicLink(outputPath.resolve("PublicHeader.h"), inputPath.resolve("PublicHeader.h"));
  }

  @Test
  public void testAppleLibraryBuildsFramework() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64,no-debug");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    assertThat(Files.exists(frameworkPath.resolve("Resources/Info.plist")), is(true));
    Path libraryPath = frameworkPath.resolve("TestLibrary");
    assertThat(Files.exists(libraryPath), is(true));
    assertThat(
        workspace.runCommand("file", libraryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleLibraryBuildsWithLinkerMap() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#shared,macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(), target, "%s")
                .resolve("libLibraries_TestLibrary_TestLibrary.dylib"));
    assertThat(Files.exists(outputPath), Matchers.is(true));
    assertThat(Files.exists(Paths.get(outputPath + "-LinkMap.txt")), Matchers.is(true));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleLibraryBuildsWithoutLinkerMapUsingFlavor() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#shared,macosx-x86_64,no-linkermap");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(), target, "%s")
                .resolve("libLibraries_TestLibrary_TestLibrary.dylib"));
    assertThat(Files.exists(outputPath), Matchers.is(true));
    assertThat(Files.exists(Paths.get(outputPath + "-LinkMap.txt")), Matchers.is(false));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleLibraryBuildsWithoutLinkerMapUsingConfigOption() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.addBuckConfigLocalOption("cxx", "linker_map_enabled", "false");
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#shared,macosx-x86_64");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(), target, "%s")
                .resolve("libLibraries_TestLibrary_TestLibrary.dylib"));
    assertThat(Files.exists(outputPath), Matchers.is(true));
    assertThat(Files.exists(Paths.get(outputPath + "-LinkMap.txt")), Matchers.is(false));
    assertThat(
        workspace.runCommand("file", outputPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleLibraryBuildsFrameworkIOS() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.IPHONESIMULATOR));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#framework,iphonesimulator-x86_64,no-debug");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    assertThat(Files.exists(frameworkPath.resolve("Info.plist")), is(true));
    Path libraryPath = frameworkPath.resolve("TestLibrary");
    assertThat(Files.exists(libraryPath), is(true));
    assertThat(
        workspace.runCommand("file", libraryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void appleLibraryBuildsMultiarchFramework() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.addBuckConfigLocalOption("cxx", "link_path_normalization_args_enabled", "true");
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
                "//Libraries/TestLibrary:TestLibrary#macosx-x86_64,macosx-arm64")
            .withAppendedFlavors(
                AppleDescriptions.FRAMEWORK_FLAVOR, AppleDebugFormat.NONE.getFlavor());
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("TestLibrary.framework"));
    Path libraryPath = frameworkPath.resolve("TestLibrary");
    assertThat(Files.exists(libraryPath), is(true));
    ProcessExecutor.Result lipoVerifyResult =
        workspace.runCommand("lipo", libraryPath.toString(), "-verify_arch", "arm64", "x86_64");
    assertEquals(lipoVerifyResult.getStderr().orElse(""), 0, lipoVerifyResult.getExitCode());
    assertThat(
        workspace.runCommand("file", libraryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void testAppleFrameworkWithDsymWithLinkerNormArgs() throws Exception {
    appleFrameworkWithDsymWithLinkerNormArgs(true);
  }

  @Test
  public void testAppleFrameworkWithDsymWithoutLinkerNormArgs() throws Exception {
    appleFrameworkWithDsymWithLinkerNormArgs(false);
  }

  private void appleFrameworkWithDsymWithLinkerNormArgs(boolean linkerNormArgs) throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_builds_something", tmp);
    workspace.addBuckConfigLocalOption(
        "cxx", "link_path_normalization_args_enabled", linkerNormArgs ? "true" : "false");
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    ProcessResult result =
        workspace.runBuckCommand(
            "build",
            "//Libraries/TestLibrary:TestLibrary#dwarf-and-dsym,framework,macosx-x86_64",
            "--config",
            "cxx.cflags=-g");
    result.assertSuccess();

    AbsPath dsymPath =
        tmp.getRoot()
            .resolve(
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    BuildTargetFactory.newInstance(
                        "//Libraries/TestLibrary:TestLibrary#dwarf-and-dsym,framework,include-frameworks,macosx-x86_64"),
                    "%s"))
            .resolve("TestLibrary.framework.dSYM");
    assertThat(Files.exists(dsymPath.getPath()), is(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbol("+[TestClass answer]", workspace, dsymPath);
  }

  @Test
  public void testAppleDynamicLibraryProducesDylib() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_library_shared", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary")
            .withAppendedFlavors(
                InternalFlavor.of("macosx-x86_64"), CxxDescriptionEnhancer.SHARED_FLAVOR);

    ProcessResult result =
        workspace.runBuckCommand(
            "build", target.getFullyQualifiedName(), "--config", "cxx.cflags=-g");
    result.assertSuccess();

    Path outputPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
  }

  @Test
  public void testAppleBinaryLinksAgainstSharedInterface() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_shared_interface", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "shlib_interfaces", "enabled");
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:Binary")
            .withAppendedFlavors(InternalFlavor.of("macosx-x86_64"));

    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path outputPath =
        workspace.getPath(BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"));
    assertThat(Files.exists(outputPath), is(true));
  }

  @Test
  public void testAppleSharedInterfaceProducesTheSameStubsForSameABI() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_shared_interface", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "shlib_interfaces", "enabled");
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    byte[] aDylibStub =
        buildDylibStub(workspace, filesystem, "//Libraries/TestLibrary:A", "Hello.dylib");
    byte[] bDylibStub =
        buildDylibStub(workspace, filesystem, "//Libraries/TestLibrary:B", "Hello.dylib");
    assertThat(aDylibStub, equalTo(bDylibStub));
  }

  private byte[] buildDylibStub(
      ProjectWorkspace workspace, ProjectFilesystem filesystem, String targetName, String libName)
      throws IOException {
    BuildTarget aLibTarget =
        BuildTargetFactory.newInstance(targetName)
            .withAppendedFlavors(
                InternalFlavor.of("macosx-x86_64"), CxxDescriptionEnhancer.SHARED_INTERFACE_FLAVOR);

    ProcessResult result = workspace.runBuckCommand("build", aLibTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path aLibOutputPath =
        workspace
            .getPath(BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), aLibTarget, "%s"))
            .resolve(libName);
    assertThat(Files.exists(aLibOutputPath), is(true));

    return Files.readAllBytes(aLibOutputPath);
  }

  @Test
  public void testAppleDynamicLibraryWithDsymWithLinkerNormArgs() throws Exception {
    appleDynamicLibraryWithDsymWithLinkerNormArgsState(true);
  }

  @Test
  public void testAppleDynamicLibraryWithDsymWithoutLinkerNormArgs() throws Exception {
    appleDynamicLibraryWithDsymWithLinkerNormArgsState(false);
  }

  public void appleDynamicLibraryWithDsymWithLinkerNormArgsState(boolean linkerNormArgs)
      throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_library_shared", tmp);
    workspace.addBuckConfigLocalOption(
        "cxx", "link_path_normalization_args_enabled", linkerNormArgs ? "true" : "false");
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary")
            .withAppendedFlavors(
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
                InternalFlavor.of("macosx-x86_64"));

    ProcessResult result =
        workspace.runBuckCommand(
            "build", target.getFullyQualifiedName(), "--config", "cxx.cflags=-g");
    result.assertSuccess();

    BuildTarget implicitTarget =
        target.withAppendedFlavors(CxxStrip.RULE_FLAVOR, StripStyle.NON_GLOBAL_SYMBOLS.getFlavor());
    Path outputPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), implicitTarget, "%s"));
    assertThat(Files.exists(outputPath), is(true));

    AbsPath dsymPath =
        tmp.getRoot()
            .resolve(
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    BuildTargetFactory.newInstance(
                        "//Libraries/TestLibrary:TestLibrary#apple-dsym,macosx-x86_64,shared"),
                    "%s.dSYM"));
    assertThat(Files.exists(dsymPath.getPath()), is(true));
    AppleDsymTestUtil.checkDsymFileHasDebugSymbol("+[TestClass answer]", workspace, dsymPath);
  }

  @Test
  public void frameworkContainsFrameworkDependencies() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(
                        AppleDebugFormat.DWARF.getFlavor(),
                        AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    Path frameworksPath = frameworkPath.resolve("Frameworks");
    assertThat(Files.exists(frameworksPath), is(true));
    Path depPath = frameworksPath.resolve("TestLibraryDep.framework/TestLibraryDep");
    assertThat(Files.exists(depPath), is(true));
    assertThat(
        workspace.runCommand("file", depPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
    Path transitiveDepPath =
        frameworksPath.resolve("TestLibraryTransitiveDep.framework/TestLibraryTransitiveDep");
    assertThat(Files.exists(transitiveDepPath), is(true));
    assertThat(
        workspace.runCommand("file", transitiveDepPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
  }

  @Test
  public void frameworkDependenciesDoNotContainTransitiveDependencies() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#framework,macosx-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(),
                    target.withAppendedFlavors(
                        AppleDebugFormat.DWARF.getFlavor(),
                        AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s")
                .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    Path frameworksPath = frameworkPath.resolve("Frameworks");
    assertThat(Files.exists(frameworksPath), is(true));
    Path depFrameworksPath = frameworksPath.resolve("TestLibraryDep.framework/Frameworks");
    assertThat(Files.exists(depFrameworksPath), is(false));
  }

  @Test
  public void noIncludeFrameworksDoesntContainFrameworkDependencies() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_with_library_dependencies", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#"
                + "dwarf-and-dsym,framework,macosx-x86_64,no-include-frameworks");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path frameworkPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s")
                .resolve("TestLibrary.framework"));
    assertThat(Files.exists(frameworkPath), is(true));
    assertThat(Files.exists(frameworkPath.resolve("Resources/Info.plist")), is(true));
    Path libraryPath = frameworkPath.resolve("TestLibrary");
    assertThat(Files.exists(libraryPath), is(true));
    assertThat(
        workspace.runCommand("file", libraryPath.toString()).getStdout().get(),
        containsString("dynamically linked shared library"));
    Path frameworksPath = frameworkPath.resolve("Contents/Frameworks");
    assertThat(Files.exists(frameworksPath), is(false));
  }

  @Test
  public void testAppleLibraryExportedHeaderSymlinkTree() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_header_symlink_tree", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary")
            .withAppendedFlavors(
                CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
                HeaderMode.SYMLINK_TREE_ONLY.getFlavor());
    ProcessResult result = workspace.runBuckCommand("build", buildTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path inputPath =
        workspace
            .getPath(
                buildTarget.getCellRelativeBasePath().getPath().toPath(filesystem.getFileSystem()))
            .toRealPath();
    Path outputPath =
        workspace
            .getPath(BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), buildTarget, "%s"))
            .toRealPath();

    assertIsSymbolicLink(
        outputPath.resolve("TestLibrary/PublicHeader.h"), inputPath.resolve("PublicHeader.h"));
  }

  @Test
  public void testAppleLibraryIsHermetic() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_library_is_hermetic", tmp);
    workspace.setUp();

    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TestLibrary#static,iphonesimulator-x86_64");
    ProcessResult first =
        workspace.runBuckCommand(
            workspace.getPath("first"), "build", target.getFullyQualifiedName());
    first.assertSuccess();

    ProcessResult second =
        workspace.runBuckCommand(
            workspace.getPath("second"), "build", target.getFullyQualifiedName());
    second.assertSuccess();

    Path objectPath =
        BuildTargetPaths.getGenPath(
                FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(
                        BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH)
                    .getBuckPaths(),
                target.withFlavors(
                    InternalFlavor.of("compile-" + sanitize("TestClass.m.o")),
                    InternalFlavor.of("iphonesimulator-x86_64")),
                "%s")
            .resolve("TestClass.m.o");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(Paths.get("first").resolve(objectPath)),
        workspace.getPath(Paths.get("second").resolve(objectPath)));
    Path libraryPath =
        BuildTargetPaths.getGenPath(
                FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(
                        BuckPaths.DEFAULT_BUCK_OUT_INCLUDE_TARGET_CONFIG_HASH)
                    .getBuckPaths(),
                target,
                "%s")
            .resolve("libTestLibrary.a");
    MoreAsserts.assertContentsEqual(
        workspace.getPath(Paths.get("first").resolve(libraryPath)),
        workspace.getPath(Paths.get("second").resolve(libraryPath)));
  }

  @Test
  public void testBuildEmptySourceAppleLibrary() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_source_targets", tmp);
    workspace.setUp();
    BuildTarget target =
        workspace
            .newBuildTarget("//:real-none#iphonesimulator-x86_64")
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    Path binaryOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), target, "%s/libreal-none.dylib"));
    assertThat(Files.exists(binaryOutput), is(true));
  }

  @Test
  public void testBuildUsingPrefixHeaderFromCxxPrecompiledHeader() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "precompiled_header", tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:library#iphonesimulator-x86_64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }

  @Test
  public void testBuildUsingPrecompiledHeaderInOtherCell() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "multicell_precompiled_header", tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:library#iphonesimulator-x86_64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }

  @Test
  public void testBuildAppleLibraryThatHasSwiftWithArgfile() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    testBuildAppleLibraryThatHasSwiftWithLocalConfig(
        ImmutableMap.of("swift", ImmutableMap.of("use_argfile", "true")));
  }

  @Test
  public void testBuildAppleLibraryThatHasSwift() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    testBuildAppleLibraryThatHasSwiftWithLocalConfig(ImmutableMap.of());
  }

  @Test
  public void testBuildAppleLibraryThatHasSwiftBuildsOnWatchOSSimulator() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHOS));

    UserFlavor watchosSimFlavor = UserFlavor.of("watchsimulator-i386", "buck boilerplate");
    testBuildAppleLibraryThatHasSwiftWithLocalConfig(watchosSimFlavor, ImmutableMap.of());
  }

  private void testBuildAppleLibraryThatHasSwiftWithLocalConfig(
      Map<String, Map<String, String>> localConfigs) throws IOException, InterruptedException {
    UserFlavor iosSimFlavor = UserFlavor.of("iphonesimulator-x86_64", "buck boilerplate");
    testBuildAppleLibraryThatHasSwiftWithLocalConfig(iosSimFlavor, localConfigs);
  }

  private void testBuildAppleLibraryThatHasSwiftWithLocalConfig(
      UserFlavor platformFlavor, Map<String, Map<String, String>> localConfigs)
      throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_source_targets", tmp);
    workspace.addBuckConfigLocalOptions(localConfigs);
    workspace.setUp();
    BuildTarget target =
        workspace
            .newBuildTarget("//:none-swift")
            .withAppendedFlavors(platformFlavor)
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    Path binaryOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), target, "%s/libnone-swift.dylib"));
    assertThat(Files.exists(binaryOutput), is(true));

    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        containsString("libswiftCore.dylib"));
  }

  @Test
  public void testBuildAppleLibraryWhereObjcUsesObjcDefinedInSwiftViaBridgingHeader()
      throws Exception {
    testDylibSwiftScenario(
        "apple_library_objc_uses_objc_from_swift_via_bridging_diff_lib", "Bar", "Foo");
  }

  @Test
  public void testBuildAppleLibraryWhereObjcUsesSwiftAcrossDifferentLibraries() throws Exception {
    testDylibSwiftScenario("apple_library_objc_uses_swift_diff_lib", "Bar", "Foo");
  }

  @Test
  public void testBuildAppleLibraryWhereSwiftUsesObjCAcrossDifferentLibraries() throws Exception {
    testDylibSwiftScenario("apple_library_swift_uses_objc_diff_lib", "Bar");
  }

  @Test
  public void testBuildAppleLibraryWhereSwiftUsesSwiftAcrossDifferentLibraries() throws Exception {
    testDylibSwiftScenario("apple_library_swift_uses_swift_diff_lib", "Bar");
  }

  @Test
  public void testBuildAppleLibraryWhereObjCUsesSwiftWithinSameLib() throws Exception {
    testDylibSwiftScenario("apple_library_objc_uses_swift_same_lib", "Mixed");
  }

  @Test
  public void testBuildAppleLibraryWhereSwiftUsesObjCWithinSameLib() throws Exception {
    testDylibSwiftScenario("apple_library_swift_uses_objc_same_lib", "Mixed");
  }

  @Test
  public void testBuildAppleLibraryWhereSwiftDefinedUsingExportFile() throws Exception {
    testDylibSwiftScenario("apple_library_swift_using_export_file", "Mixed");
  }

  public void testDylibSwiftScenario(String scenario, String targetName) throws Exception {
    testDylibSwiftScenario(scenario, targetName, targetName);
  }

  public void testDylibSwiftScenario(
      String scenario, String dylibTargetName, String swiftRuntimeDylibTargetName)
      throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");
    BuildTarget dylibTarget =
        workspace
            .newBuildTarget(String.format("//:%s#macosx-x86_64", dylibTargetName))
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    ProcessResult result = workspace.runBuckCommand("build", dylibTarget.getFullyQualifiedName());
    result.assertSuccess();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    String dylibPathFormat = "%s/" + String.format("lib%s.dylib", dylibTargetName);
    Path binaryOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), dylibTarget, dylibPathFormat));
    assertThat(Files.exists(binaryOutput), is(true));

    BuildTarget swiftRuntimeTarget =
        workspace
            .newBuildTarget(String.format("//:%s#macosx-x86_64", swiftRuntimeDylibTargetName))
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR);
    String swiftRuntimePathFormat =
        "%s/" + String.format("lib%s.dylib", swiftRuntimeDylibTargetName);
    Path swiftRuntimeBinaryOutput =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), swiftRuntimeTarget, swiftRuntimePathFormat));
    assertThat(
        workspace.runCommand("otool", "-L", swiftRuntimeBinaryOutput.toString()).getStdout().get(),
        containsString("libswiftCore.dylib"));
  }

  @Test
  public void testBuildAppleLibraryUsingBridingHeaderAndSwiftDotH() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "import_current_module_via_bridging_header", tmp);
    workspace.setUp();
    BuildTarget target = workspace.newBuildTarget("//:Greeter");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }

  @Test
  public void testBuildAppleLibraryWhereModularObjcAndSwiftUseEachOther() throws Exception {
    testModularScenario("apple_library_modular_objc_swift_bidirectional", "Mixed");
  }

  @Test
  public void testBuildAppleLibraryWhereModularObjcUsesSwiftDiffLib() throws Exception {
    testModularScenario("apple_library_modular_objc_uses_swift_diff_lib", "Bar");
  }

  @Test
  public void testBuildAppleLibraryWhereModularObjcUsesSwiftSameLib() throws Exception {
    testModularScenario("apple_library_modular_objc_uses_swift_same_lib", "Mixed");
  }

  @Test
  public void testBuildAppleLibraryWhereModularSwiftUsesObjcDiffLib() throws Exception {
    testModularScenario("apple_library_modular_swift_uses_objc_diff_lib", "Bar");
  }

  @Test
  public void testBuildAppleLibraryWhereModularSwiftUsesObjcSameLib() throws Exception {
    testModularScenario("apple_library_modular_swift_uses_objc_same_lib", "Mixed");
  }

  @Test
  public void testBuildAppleLibraryWhereModularSwiftUsesHeaderModuleMap() throws Exception {
    testModularScenario("headers_modulemap", "Test");
  }

  @Test
  public void testTargetSDKVersion() throws Exception {
    testModularScenario("target_sdk_version", "Binary");
    testModularScenario("target_sdk_version", "Library");
    testModularScenarioWithFlavor("target_sdk_version", "Test", Optional.empty());
  }

  private void testModularScenario(String scenario, String targetName) throws Exception {
    testModularScenarioWithFlavor(
        scenario, targetName, Optional.of(CxxDescriptionEnhancer.SHARED_FLAVOR));
  }

  private ProjectWorkspace testModularScenarioWithFlavor(
      String scenario, String targetName, Optional<Flavor> flavor) throws Exception {
    return testModularScenarioWithFlavorAndLocalConfigs(
        scenario, targetName, flavor, ImmutableMap.of());
  }

  private ProjectWorkspace testModularScenarioWithFlavorAndLocalConfigs(
      String scenario,
      String targetName,
      Optional<Flavor> flavor,
      Map<String, Map<String, String>> localConfigs)
      throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOptions(localConfigs);
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");
    workspace.addBuckConfigLocalOption("cxx", "cflags", "-fmodules");
    BuildTarget target =
        workspace.newBuildTarget(String.format("//:%s#iphonesimulator-x86_64", targetName));

    if (flavor.isPresent()) {
      target = target.withAppendedFlavors(flavor.get());
    }

    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
    return workspace;
  }

  private static void assertIsSymbolicLink(Path link, Path target) throws IOException {
    assertTrue(Files.isSymbolicLink(link));
    assertEquals(target, Files.readSymbolicLink(link));
  }

  @Test
  public void testAppleLibraryTargetSpecificSDKVersion() throws IOException, InterruptedException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_target_specific_sdk_version", tmp);
    workspace.addBuckConfigLocalOption("apple", "target_sdk_version_linker_flag", "true");
    workspace.setUp();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    // Build dylib without target specific SDK version, i.e., latest SDK deployment target

    BuildTarget nonSpecificTarget =
        BuildTargetFactory.newInstance("//Libraries/TestLibrary:TestLibrary#shared,macosx-x86_64");
    ProcessResult result =
        workspace.runBuckCommand("build", nonSpecificTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path nonSpecificTargetPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                nonSpecificTarget,
                "%s/libLibraries_TestLibrary_TestLibrary.dylib"));
    assertTrue(Files.exists(nonSpecificTargetPath));

    // Build dylib with target specific SDK version (10.14 in BUCK file)

    BuildTarget sdkVersionTarget =
        BuildTargetFactory.newInstance(
            "//Libraries/TestLibrary:TargetSpecificVersionLibrary#shared,macosx-x86_64");
    result = workspace.runBuckCommand("build", sdkVersionTarget.getFullyQualifiedName());
    result.assertSuccess();

    Path sdkVersionTargetPath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(),
                sdkVersionTarget,
                "%s/libLibraries_TestLibrary_TargetSpecificVersionLibrary.dylib"));
    assertTrue(Files.exists(sdkVersionTargetPath));

    // Extract loader command to verify deployment target

    Optional<String> nonSpecificTargetBuildVersion =
        getOtoolLoaderCommandByName(workspace, nonSpecificTargetPath, "LC_BUILD_VERSION");
    assertTrue(nonSpecificTargetBuildVersion.isPresent());
    Optional<String> specificSDKTargetBuildVersion =
        getOtoolLoaderCommandByName(workspace, sdkVersionTargetPath, "LC_BUILD_VERSION");
    assertTrue(specificSDKTargetBuildVersion.isPresent());

    // Verify that only target specific dylib has deployment set to 10.14

    assertThat(nonSpecificTargetBuildVersion.get(), not(containsString("minos 10.14")));
    assertThat(specificSDKTargetBuildVersion.get(), containsString("minos 10.14"));
  }

  @Test
  public void testAppleLibraryDebugPrefixMap() throws Exception {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_library_debug_info", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");
    workspace.addBuckConfigLocalOption("swift", "use_debug_prefix_map", "true");
    BuildTarget target = workspace.newBuildTarget("//:Bar#macosx-x86_64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    BuildTarget objcObjectFileTarget =
        BuildTargetFactory.newInstance("//:Bar#compile-Hello.m.o7d1569b2,macosx-x86_64");
    Path objcObjectFilePath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), objcObjectFileTarget, "%s/Hello.m.o"));
    assertTrue(Files.exists(objcObjectFilePath));

    Optional<String> maybeStdOut =
        workspace.runCommand("dwarfdump", objcObjectFilePath.toString()).getStdout();
    assertTrue(maybeStdOut.isPresent());
    String objcDwarf = maybeStdOut.get();
    assertThat(objcDwarf, containsString("DW_AT_LLVM_sysroot\t(\"/APPLE_SDKROOT\")"));
    assertThat(objcDwarf, containsString("DW_AT_comp_dir\t(\".\")"));
    assertThat(objcDwarf, containsString("DW_AT_decl_file\t(\"/APPLE_SDKROOT/usr/include"));
    assertThat(
        objcDwarf,
        containsString(
            "DW_AT_LLVM_include_path\t(\"/APPLE_SDKROOT/System/Library/Frameworks/Foundation.framework\")"));

    // ensure there are no absolute paths
    Pattern pattern = Pattern.compile("\\(\"(/[^\"]+)\"\\)");
    Matcher m = pattern.matcher(objcDwarf);
    while (m.find()) {
      assertTrue(m.group(1).startsWith("/APPLE_"));
    }

    BuildTarget swiftObjectFileTarget =
        BuildTargetFactory.newInstance("//:Foo#apple-swift-compile,macosx-x86_64");
    Path swiftObjectFilePath =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem.getBuckPaths(), swiftObjectFileTarget, "%s/Foo.o"));
    assertTrue(Files.exists(swiftObjectFilePath));

    maybeStdOut = workspace.runCommand("dwarfdump", swiftObjectFilePath.toString()).getStdout();
    assertTrue(maybeStdOut.isPresent());
    String swiftDwarf = maybeStdOut.get();
    assertThat(swiftDwarf, containsString("DW_AT_comp_dir\t(\".\")"));
    assertThat(
        swiftDwarf,
        containsString(
            "DW_AT_LLVM_include_path\t(\"/APPLE_SDKROOT/usr/lib/swift/Swift.swiftmodule/x86_64-apple-macos.swiftinterface\")"));

    m = pattern.matcher(swiftDwarf);
    while (m.find()) {
      // Compiler bug does not prefix map the sysroot, fixed in next Swift release
      if (m.group(1).endsWith("MacOSX.sdk")) {
        continue;
      }
      assertTrue(m.group(1).startsWith("/APPLE_"));
    }
  }

  public static Optional<String> getOtoolLoaderCommandByName(
      ProjectWorkspace workspace, Path executable, String commandName)
      throws IOException, InterruptedException {
    Optional<String> maybeStdOut =
        workspace.runCommand("otool", "-l", executable.toString()).getStdout();

    return maybeStdOut.flatMap(
        stdOut -> {
          String commandStart = String.format("cmd %s", commandName);
          int commandStartIndex = stdOut.indexOf(commandStart);
          if (commandStartIndex < 0) {
            return Optional.empty();
          }

          int startOfNextCommand =
              stdOut.indexOf(
                  "Load command",
                  Math.min(commandStartIndex + commandStart.length(), stdOut.length() - 1));
          if (startOfNextCommand < 0) {
            return Optional.empty();
          }

          return Optional.of(stdOut.substring(commandStartIndex, startOfNextCommand));
        });
  }
}
