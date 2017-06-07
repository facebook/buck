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

package com.facebook.buck.cxx;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class PrebuiltCxxLibraryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void prebuiltCxxLibraryFromGenrule() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt_cxx_from_genrule", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    final String binaryTargetString = "//core:binary";
    final String staticBuildTargetString = "//test_lib:bar#static,default";
    final String sharedBuildTargetString = "//test_lib:bar#shared,default";

    workspace.runBuckBuild(binaryTargetString).assertSuccess();

    // Clean everything
    workspace.runBuckCommand("clean").assertSuccess();

    // After a clean make sure that everything was in cache.
    workspace.runBuckBuild(binaryTargetString).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetWasFetchedFromCache(binaryTargetString);

    // Make sure that the static library isn't cached but can still be built.
    workspace.runBuckBuild(staticBuildTargetString).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(staticBuildTargetString);

    workspace.runBuckBuild(sharedBuildTargetString).assertSuccess();
    workspace.runBuckBuild(binaryTargetString).assertSuccess();
    buildLog = workspace.getBuildLog();

    // Now make sure that nothing is built.
    // Since we haven't cleaned some will be just from cache.
    // Others will be MATCHING_RULE_KEY
    for (BuildTarget buildTarget : buildLog.getAllTargets()) {
      buildLog.assertNotTargetBuiltLocally(buildTarget.toString());
    }
  }

  @Test
  public void prebuiltCxxLibraryFromGenruleChangeFile() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt_cxx_from_genrule", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    final String binaryTargetString = "//core:binary";

    workspace.runBuckBuild(binaryTargetString).assertSuccess();
    // Clean everything
    workspace.runBuckCommand("clean").assertSuccess();

    // Make sure that deps are pulled from the cache.
    workspace.replaceFileContents("core/binary.cpp", "return bar();", "return bar() + 1;");

    workspace.runBuckBuild(binaryTargetString).assertSuccess();
  }
}
