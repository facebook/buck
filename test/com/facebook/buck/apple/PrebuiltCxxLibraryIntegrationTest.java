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

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PrebuiltCxxLibraryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
  }

  @Test
  public void testProjectGenerator() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt_cxx_library", tmp);
    workspace.setUp();
    workspace.runBuckCommand("project", "//src:main").assertSuccess();

    ProcessExecutor.Result result =
        workspace.runCommand(
            "xcodebuild",

            // "json" output.
            "-json",

            // Make sure the output stays in the temp folder.
            "-derivedDataPath",
            "xcode-out/",

            // Build the project that we just generated
            "-workspace",
            "src/main.xcworkspace",
            "-scheme",
            "main",

            // Build for iphonesimulator
            "-arch",
            "x86_64",
            "-sdk",
            "macosx");
    result.getStderr().ifPresent(System.err::print);
    assertEquals("xcodebuild should succeed", 0, result.getExitCode());
  }

  @Test
  public void testBuild() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt_cxx_library", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//src:main#macosx-x86_64");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }
}
