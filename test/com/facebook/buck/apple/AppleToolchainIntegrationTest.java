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

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.cxx.CxxToolchainHelper;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AppleToolchainIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() {
    Assume.assumeThat(Platform.detect(), not(Platform.WINDOWS));
  }

  @Test
  public void testBuildWithCustomAppleToolchain() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);
    workspace.addBuckConfigLocalOption(
        "apple", "toolchain_set_target", "//apple_toolchain:toolchain");
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:TestApp#iphoneos-arm64");
    assertEquals("signed by codesign\n", workspace.getFileContents(output.resolve("app_signed")));
    RelPath sdkPath =
        BuildTargetPaths.getGenPath(
            workspace.getProjectFileSystem().getBuckPaths(),
            BuildTargetFactory.newInstance("//apple_toolchain/tools:gen-sdk"),
            "%s");
    assertEquals(
        String.format(
            "strip Tx:%n"
                + "linker: input:%n"
                + BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:TestLib#iphoneos-arm64,static"),
                    "%s")
                + "/libTestLib.static.secret%n"
                + "archive:%n"
                + "object: compile output: source code 1%n"
                + "object: compile output: source code 2%n"
                + "ranlib applied.%n"
                + "linker: fpath: %s/sdk/Frameworks%n"
                + "linker: frameworks: Foundation,UIKit%n"
                + "linker: lpath: %s/sdk/lib%n"
                + "linker: libs: objc%n",
            sdkPath,
            sdkPath),
        workspace.getFileContents(output.resolve("TestApp")));
  }

  @Test
  public void testBuildWithCustomAppleToolchainMultiArch() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);
    workspace.addBuckConfigLocalOption(
        "apple", "toolchain_set_target", "//apple_toolchain:toolchain");
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:TestApp#iphoneos-arm64,iphoneos-armv7");
    assertEquals("signed by codesign\n", workspace.getFileContents(output.resolve("app_signed")));
    RelPath sdkPath =
        BuildTargetPaths.getGenPath(
            workspace.getProjectFileSystem().getBuckPaths(),
            BuildTargetFactory.newInstance("//apple_toolchain/tools:gen-sdk"),
            "%s");
    assertEquals(
        String.format(
            "universal file:%n"
                + "strip Tx:%n"
                + "linker: input:%n"
                + BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:TestLib#iphoneos-arm64,static"),
                    "%s")
                + "/libTestLib.static.secret%n"
                + "archive:%n"
                + "object: compile output: source code 1%n"
                + "object: compile output: source code 2%n"
                + "ranlib applied.%n"
                + "linker: fpath: %s/sdk/Frameworks%n"
                + "linker: frameworks: Foundation,UIKit%n"
                + "linker: lpath: %s/sdk/lib%n"
                + "linker: libs: objc%n"
                + "universal file:%n"
                + "strip Tx:%n"
                + "linker: input:%n"
                + BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:TestLib#iphoneos-armv7,static"),
                    "%s")
                + "/libTestLib.static.secret%n"
                + "archive:%n"
                + "object: compile output: source code 1%n"
                + "object: compile output: source code 2%n"
                + "ranlib applied.%n"
                + "linker: fpath: apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/Frameworks%n"
                + "linker: frameworks: Foundation,UIKit%n"
                + "linker: lpath: apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/lib%n"
                + "linker: libs: objc%n",
            sdkPath,
            sdkPath),
        workspace.getFileContents(output.resolve("TestApp")));
  }

  @Test
  public void testBuildWithDsymutilWorkaround() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);
    workspace.addBuckConfigLocalOption(
        "apple", "toolchain_set_target", "//apple_toolchain:toolchain_dsymutil");
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:TestApp#iphoneos-arm64");
    assertEquals("signed by codesign\n", workspace.getFileContents(output.resolve("app_signed")));
    assertEquals(
        String.format(
            "strip Tx:%n"
                + "linker: input:%n"
                + BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:TestLib#iphoneos-arm64,static"),
                    "%s")
                + "/libTestLib.static.secret%n"
                + "archive:%n"
                + "object: compile output: source code 1%n"
                + "object: compile output: source code 2%n"
                + "ranlib applied.%n"
                + "linker: fpath: apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/Frameworks%n"
                + "linker: frameworks: Foundation,UIKit%n"
                + "linker: lpath: apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/lib%n"
                + "linker: libs: objc%n"),
        workspace.getFileContents(output.resolve("TestApp")));
  }

  @Test
  public void testBuildWithCustomAppleToolchainWithSwift() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);
    workspace.addBuckConfigLocalOption(
        "apple", "toolchain_set_target", "//apple_toolchain:toolchain_swift");
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:TestSwiftBinary#iphoneos-arm64");
    RelPath swiftLibraryPath =
        BuildTargetPaths.getGenPath(
            workspace.getProjectFileSystem().getBuckPaths(),
            BuildTargetFactory.newInstance("//:SwiftLibrary#iphoneos-arm64,swift-compile"),
            "%s");
    RelPath anotherSwiftLibraryPath =
        BuildTargetPaths.getGenPath(
            workspace.getProjectFileSystem().getBuckPaths(),
            BuildTargetFactory.newInstance("//:AnotherSwiftLibrary#iphoneos-arm64,swift-compile"),
            "%s");
    RelPath companionLibraryPath =
        BuildTargetPaths.getGenPath(
            workspace.getProjectFileSystem().getBuckPaths(),
            BuildTargetFactory.newInstance("//:SwiftCompanionLibrary#iphoneos-arm64,swift-compile"),
            "%s");
    RelPath sdkRelPath =
        BuildTargetPaths.getGenPath(
            workspace.getProjectFileSystem().getBuckPaths(),
            BuildTargetFactory.newInstance("//apple_toolchain/tools:gen-sdk"),
            "%s/sdk");
    Path sdkPath = workspace.getDestPath().resolve(sdkRelPath.getPath());

    assertEquals(
        String.format(
            "linker: input:%n"
                + swiftLibraryPath
                + "/SwiftLibrary.o%n"
                + "swift compile: swift code%n"
                + "swift flags: -sdk "
                + sdkPath
                + "%n"
                + "swift flags: -prefix-serialized-debug-info%n"
                + "linker: input:%n"
                + companionLibraryPath
                + "/SwiftCompanionLibrary.o%n"
                + "swift compile: swift source 1%n"
                + "swift compile: swift source 2%n"
                + "swift flags: -sdk "
                + sdkPath
                + "%n"
                + "swift flags: -prefix-serialized-debug-info%n"
                + "linker: input:%n"
                + anotherSwiftLibraryPath
                + "/AnotherSwiftLibrary.o%n"
                + "swift compile: extra swift code%n"
                + "swift flags: -sdk "
                + sdkPath
                + "%n"
                + "swift flags: -prefix-serialized-debug-info%n"
                + "linker: fpath: apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/Frameworks%n"
                + "linker: frameworks: Foundation%n"
                + "linker: lpath: /test/linking,@executable_path/linking,@loader_path/linking%n"
                + "linker: libs: %n"
                + "linker: ast_paths: %s/SwiftLibrary.swiftmodule,%s/SwiftCompanionLibrary.swiftmodule,%s/AnotherSwiftLibrary.swiftmodule%n"
                + "linker: rpath: /test/runtime_run,@executable_path/runtime_run,@loader_path/runtime_run%n",
            swiftLibraryPath,
            companionLibraryPath,
            anotherSwiftLibraryPath),
        workspace.getFileContents(output));
  }

  @Test
  public void testBuildWithCustomAppleToolchainAndConfig() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);
    workspace.addBuckConfigLocalOption(
        "apple", "toolchain_set_target", "//apple_toolchain:toolchain");
    workspace.addBuckConfigLocalOption("cxx#iphoneos-arm64", "cflags", "-unused-flag");
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:TestApp#iphoneos-arm64");
    assertEquals("signed by codesign\n", workspace.getFileContents(output.resolve("app_signed")));
    RelPath sdkPath =
        BuildTargetPaths.getGenPath(
            workspace.getProjectFileSystem().getBuckPaths(),
            BuildTargetFactory.newInstance("//apple_toolchain/tools:gen-sdk"),
            "%s");
    assertEquals(
        String.format(
            "strip Tx:%n"
                + "linker: input:%n"
                + BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(),
                    BuildTargetFactory.newInstance("//:TestLib#iphoneos-arm64,static"),
                    "%s")
                + "/libTestLib.static.secret%n"
                + "archive:%n"
                + "object: compile output: source code 1%n"
                + "object: compile output: source code 2%n"
                + "ranlib applied.%n"
                + "linker: fpath: %s/sdk/Frameworks%n"
                + "linker: frameworks: Foundation,UIKit%n"
                + "linker: lpath: %s/sdk/lib%n"
                + "linker: libs: objc%n",
            sdkPath,
            sdkPath),
        workspace.getFileContents(output.resolve("TestApp")));
  }

  @Test
  public void testAppleToolchainStripArgs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);
    workspace.addBuckConfigLocalOption(
        "apple", "toolchain_set_target", "//apple_toolchain:toolchain");
    workspace.setUp();

    verifyStripArgs(workspace, "strip-debug", "S");
    verifyStripArgs(workspace, "strip-non-global", "Tx");
    verifyStripArgs(workspace, "strip-all", "rTu");
  }

  private void verifyStripArgs(ProjectWorkspace workspace, String stripFlavor, String expectedArgs)
      throws IOException {
    Path output = workspace.buildAndReturnOutput("//:TestApp#iphoneos-arm64," + stripFlavor);
    assertTrue(
        workspace
            .getFileContents(output.resolve("TestApp"))
            .startsWith(String.format("strip %s:", expectedArgs)));
  }
}
