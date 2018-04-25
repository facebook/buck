/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.swift;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.apple.AppleDebugFormat;
import com.facebook.buck.apple.AppleDescriptions;
import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.CoreMatchers;
import org.junit.Rule;
import org.junit.Test;

public class SwiftTestIOSIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testAppleTestToWorkWithSwift() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "swift_test", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(AppleTestBuilder.class).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target = workspace.newBuildTarget("//:MixedTest#iphonesimulator-x86_64");
    ProcessResult result = workspace.runBuckCommand("test", target.getFullyQualifiedName());
    result.assertSuccess();

    Path binaryOutput =
        workspace
            .getPath(
                BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        InternalFlavor.of("iphonesimulator-x86_64"),
                        InternalFlavor.of("apple-test-bundle"),
                        AppleDebugFormat.DWARF.getFlavor(),
                        LinkerMapMode.NO_LINKER_MAP.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s/MixedTest.xctest"))
            .resolve("MixedTest");
    assertThat(Files.exists(binaryOutput), CoreMatchers.is(true));

    assertThat(
        workspace.runCommand("file", binaryOutput.toString()).getStdout().get(),
        containsString("bundle x86_64"));
    assertThat(
        workspace.runCommand("otool", "-hv", binaryOutput.toString()).getStdout().get(),
        containsString("X86_64"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        containsString("XCTest.framework/XCTest"));
    assertThat(
        workspace.runCommand("otool", "-l", binaryOutput.toString()).getStdout().get(),
        containsString("@loader_path/Frameworks"));
  }

  @Test
  public void testSwiftInHostAndTestBundleAppleLibrary() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "swift_test_with_host", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(AppleTestBuilder.class).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target = workspace.newBuildTarget("//:swifttest#iphonesimulator-x86_64");
    ProcessResult result = workspace.runBuckCommand("test", target.getFullyQualifiedName());
    result.assertSuccess();

    Path binaryOutput =
        workspace
            .getPath(
                BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        InternalFlavor.of("iphonesimulator-x86_64"),
                        InternalFlavor.of("apple-test-bundle"),
                        AppleDebugFormat.DWARF.getFlavor(),
                        LinkerMapMode.NO_LINKER_MAP.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s/swifttest.xctest"))
            .resolve("swifttest");
    assertThat(Files.exists(binaryOutput), CoreMatchers.is(true));

    assertThat(
        workspace.runCommand("file", binaryOutput.toString()).getStdout().get(),
        containsString("bundle x86_64"));
    assertThat(
        workspace.runCommand("otool", "-hv", binaryOutput.toString()).getStdout().get(),
        containsString("X86_64"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        containsString("XCTest.framework/XCTest"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        containsString("@rpath/libswiftCore.dylib"));
    assertThat(
        workspace.runCommand("otool", "-l", binaryOutput.toString()).getStdout().get(),
        containsString("@loader_path/Frameworks"));
  }

  @Test
  public void testSwiftInHostAndTestBundleAppleLibraryMacOS() throws Exception {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "swift_test_with_host", tmp);
    workspace.setUp();
    workspace.copyRecursively(
        TestDataHelper.getTestDataDirectory(AppleTestBuilder.class).resolve("fbxctest"),
        Paths.get("fbxctest"));
    workspace.addBuckConfigLocalOption("apple", "xctool_path", "fbxctest/bin/fbxctest");

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildTarget target = workspace.newBuildTarget("//:swifttest#macosx-x86_64");
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            target.getFullyQualifiedName(),
            "--config",
            "cxx.default_platform=macosx-x86_64");
    result.assertSuccess();

    Path binaryOutput =
        workspace
            .getPath(
                BuildTargets.getGenPath(
                    filesystem,
                    target.withAppendedFlavors(
                        InternalFlavor.of("macosx-x86_64"),
                        InternalFlavor.of("apple-test-bundle"),
                        AppleDebugFormat.DWARF.getFlavor(),
                        LinkerMapMode.NO_LINKER_MAP.getFlavor(),
                        AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
                    "%s/swifttest.xctest"))
            .resolve("Contents/MacOS/swifttest/");
    assertThat(Files.exists(binaryOutput), CoreMatchers.is(true));

    assertThat(
        workspace.runCommand("file", binaryOutput.toString()).getStdout().get(),
        containsString("bundle x86_64"));
    assertThat(
        workspace.runCommand("otool", "-hv", binaryOutput.toString()).getStdout().get(),
        containsString("X86_64"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        containsString("XCTest.framework/Versions/A/XCTest"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        not(containsString("@rpath/libswiftCore.dylib")));
  }
}
