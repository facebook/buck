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
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleDescriptions;
import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.ApplePlatform;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;

import org.hamcrest.CoreMatchers;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SwiftIOSBundleIntegrationTest {
  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void simpleApplicationBundle() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR),
        is(true));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_swift_application_bundle",
        tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

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
  }

  @Test
  public void swiftWithSwiftDependenciesBuildsSomething() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR),
        is(true));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "swift_on_swift",
        tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = workspace.newBuildTarget("//:parent");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    target = workspace.newBuildTarget("//:libparent");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

    target = workspace.newBuildTarget("//:ios-sos#iphonesimulator-x86_64,no-debug");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();

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
  }

  @Test
  public void swiftLibraryWhenLinkStyleIsNotSharedDoesNotProduceDylib() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "swift_on_swift", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        ":ios-parent#iphonesimulator-x86_64",
        "--config",
        "cxx.cflags=-g");
    result.assertSuccess();

    Path binaryOutput = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("ios-parent#iphonesimulator-x86_64");
    assertThat(Files.exists(binaryOutput), CoreMatchers.is(true));

    assertThat(
        workspace.runCommand("file", binaryOutput.toString()).getStdout().get(),
        containsString("executable"));
    assertThat(
        workspace.runCommand("otool", "-hv", binaryOutput.toString()).getStdout().get(),
        containsString("X86_64"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        not(containsString("libdep1.dylib")));

    Path dep1Output = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("iosdep1#iphonesimulator-x86_64,swift-compile")
        .resolve("libiosdep1.dylib");
    assertThat(Files.notExists(dep1Output), CoreMatchers.is(true));
  }

  @Test
  public void swiftLibraryWhenLinkStyleIsSharedShouldProduceDylib() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "swift_on_swift", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "build",
        ":ios-parent-dynamic#iphonesimulator-x86_64",
        "--config",
        "cxx.cflags=-g");
    result.assertSuccess();

    Path binaryOutput = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("ios-parent-dynamic#iphonesimulator-x86_64");
    assertThat(Files.exists(binaryOutput), CoreMatchers.is(true));

    assertThat(
        workspace.runCommand("file", binaryOutput.toString()).getStdout().get(),
        containsString("executable"));
    assertThat(
        workspace.runCommand("otool", "-hv", binaryOutput.toString()).getStdout().get(),
        containsString("X86_64"));
    assertThat(
        workspace.runCommand("otool", "-L", binaryOutput.toString()).getStdout().get(),
        containsString("libiosdep1.dylib"));

    Path parentOutput = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("ios-parent-dynamic#iphonesimulator-x86_64,swift-compile")
        .resolve("ios_parent_dynamic.swiftmodule");
    assertThat(Files.exists(parentOutput), CoreMatchers.is(true));

    Path dep1Output = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("iosdep1#iphonesimulator-x86_64")
        .resolve("libiosdep1.dylib");
    assertThat(Files.exists(dep1Output), CoreMatchers.is(true));
  }
}
