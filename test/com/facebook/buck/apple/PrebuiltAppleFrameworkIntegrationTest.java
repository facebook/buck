/*
 * Copyright 2014-present Facebook, Inc.
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
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class PrebuiltAppleFrameworkIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testPrebuiltAppleFrameworkBuildsSomething() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "prebuilt_apple_framework_builds", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//prebuilt:BuckTest");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    assertTrue(Files.exists(workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))));
  }

  @Test
  public void testPrebuiltAppleFrameworkLinks() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "prebuilt_apple_framework_links", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//app:TestApp");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();

    Path testBinaryPath = workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"));
    assertTrue(Files.exists(testBinaryPath));

    ProcessExecutor.Result otoolResult = workspace.runCommand(
        "otool", "-L", testBinaryPath.toString());
    assertEquals(0, otoolResult.getExitCode());
    Assert.assertThat(
        otoolResult.getStdout().orElse(""),
        containsString("@rpath/BuckTest.framework/BuckTest"));
    Assert.assertThat(
        otoolResult.getStdout().orElse(""),
        not(containsString("BuckTest.dylib")));
  }

  @Test
  public void testPrebuiltAppleFrameworkCopiedToBundle() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "prebuilt_apple_framework_links", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.getDestPath());

    BuildTarget target =
        BuildTargetFactory.newInstance("//app:TestAppBundle#dwarf-and-dsym,include-frameworks");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();


    Path includedFramework = workspace.getPath(BuildTargets.getGenPath(filesystem, target, "%s"))
        .resolve("TestAppBundle.app")
        .resolve("Frameworks")
        .resolve("BuckTest.framework");
    assertTrue(Files.isDirectory(includedFramework));
  }

}
