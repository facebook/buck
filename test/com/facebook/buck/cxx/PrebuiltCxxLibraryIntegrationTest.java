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

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import org.hamcrest.Matchers;
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
    String binaryTargetString = "//core:binary";

    ProcessResult result = workspace.runBuckCommand("run", binaryTargetString);
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.equalTo("5\n"));
  }

  @Test
  public void prebuiltCxxLibraryFromGenruleChangeFile() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "prebuilt_cxx_from_genrule", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    String binaryTargetString = "//core:binary";

    ProcessResult result = workspace.runBuckCommand("run", binaryTargetString);
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.equalTo("5\n"));

    // Make sure that deps are pulled from the cache.
    workspace.replaceFileContents("test_lib/bar.cpp", "return 5;", "return 6;");

    result = workspace.runBuckCommand("run", binaryTargetString);
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.equalTo("6\n"));
  }
}
