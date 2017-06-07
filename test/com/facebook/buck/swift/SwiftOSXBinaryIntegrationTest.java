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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.ApplePlatform;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class SwiftOSXBinaryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths(true);

  @Test
  public void swiftHelloWorldRunsAndPrintsMessageOnOSX() throws IOException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "helloworld", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand("run", ":hello-bin#macosx-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), equalTo("Hello, \uD83C\uDF0E!\n"));
  }

  @Test
  public void changingSourceOfSwiftLibraryDepRelinksBinary() throws IOException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "helloworld", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand("run", ":hello-bin#macosx-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), equalTo("Hello, \uD83C\uDF0E!\n"));

    workspace.replaceFileContents("main.swift", "Hello", "Goodbye");

    ProjectWorkspace.ProcessResult secondRunResult =
        workspace.runBuckCommand("run", ":hello-bin#macosx-x86_64");
    secondRunResult.assertSuccess();
    assertThat(secondRunResult.getStdout(), equalTo("Goodbye, \uD83C\uDF0E!\n"));
  }

  @Test
  public void objcMixedSwiftRunsAndPrintsMessageOnOSX() throws IOException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "objc_mix_swift", tmp);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("swift", "version", "2.3");

    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand("run", ":DemoMix#macosx-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), equalTo("Hello Swift\n"));
  }

  @Test
  public void swiftCallingObjCRunsAndPrintsMessageOnOSX() throws IOException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "swift_calls_objc", tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand("run", ":SwiftCallsObjC#macosx-x86_64");
    runResult.assertSuccess();
    assertThat(runResult.getStdout(), containsString("Hello ObjC\n"));
  }

  @Test
  public void testGeneratedModuleWithUmbrellaHeaderFile() throws IOException, InterruptedException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "modules_import", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = workspace.newBuildTarget("//:one#macosx-x86_64");
    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand(
            "build",
            "--config",
            "swift.version=2.3",
            target
                .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
                .getFullyQualifiedName());
    runResult.assertSuccess();

    Path headerMapSymlinkTreePath =
        workspace.getPath(
            CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
                filesystem,
                target.withFlavors(),
                HeaderVisibility.PUBLIC,
                CxxPreprocessables.HeaderMode.SYMLINK_TREE_WITH_HEADER_MAP.getFlavor()));
    Path buckModuleMap = headerMapSymlinkTreePath.resolve("buck.modulemap");
    Optional<String> fileContent = filesystem.readFileIfItExists(buckModuleMap);
    assertThat(fileContent.isPresent(), equalTo(true));
    assertThat(fileContent.get(), containsString("umbrella header \"one/one.h\""));
  }

  @Test
  public void testGeneratedModuleWithUmbrellaDirectory() throws IOException, InterruptedException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "modules_import", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target = workspace.newBuildTarget("//:second-one#macosx-x86_64");
    ProjectWorkspace.ProcessResult runResult =
        workspace.runBuckCommand(
            "build",
            "--config",
            "swift.version=2.3",
            target
                .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
                .getFullyQualifiedName());
    runResult.assertSuccess();

    Path headerMapSymlinkTreePath =
        workspace.getPath(
            CxxDescriptionEnhancer.getHeaderSymlinkTreePath(
                filesystem,
                target.withFlavors(),
                HeaderVisibility.PUBLIC,
                CxxPreprocessables.HeaderMode.SYMLINK_TREE_WITH_HEADER_MAP.getFlavor()));
    Path buckModuleMap = headerMapSymlinkTreePath.resolve("buck.modulemap");
    Optional<String> fileContentOptional = filesystem.readFileIfItExists(buckModuleMap);
    assertThat(fileContentOptional.isPresent(), equalTo(true));
    String fileContent = fileContentOptional.get();
    assertThat(fileContent, containsString("module second_one"));
    assertThat(fileContent, not(containsString("module second-one")));
    assertThat(fileContent, containsString("umbrella \"second_one\""));
  }
}
