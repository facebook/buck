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
import static org.junit.Assume.assumeThat;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.ApplePlatform;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.hamcrest.CoreMatchers;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class SwiftTestIOSIntegrationTest {
  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testAppleTestToWorkWithSwift() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR),
        is(true));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "swift_test",
        tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = workspace.newBuildTarget("//:MixedTest#iphonesimulator-x86_64");
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "test",
        target.getFullyQualifiedName());
    result.assertSuccess();

    Path binaryOutput = tmp.getRoot()
        .resolve(filesystem.getBuckPaths().getGenDir())
        .resolve("MixedTest#apple-test-bundle,dwarf,iphonesimulator-x86_64,no-include-frameworks")
        .resolve("MixedTest.xctest")
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
}
