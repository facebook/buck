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

package com.facebook.buck.cli;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class SuggestPartitionIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  /**
   * We take a small Java project with three java_library() rules: //lib:lib, //app:app, and
   * //third-party:third-party. We run `buck suggest //lib:lib` and replace lib/BUCK with its
   * output. We verify that the project still builds with the new rule definitions for //lib:lib.
   */
  @Test
  public void suggestPartitionAndVerifyItWorksInPlaceOfTheOriginalContents() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "suggest_java_partition", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//app:app").assertSuccess();

    ProcessResult result = workspace.runBuckCommand("suggest", "//lib:lib");
    result.assertSuccess();

    // Overwrite lib/BUCK with output of `buck suggest`.
    workspace.writeContentsToPath(result.getStdout(), "lib/BUCK");
    workspace.verify(Paths.get("lib"));

    workspace
        .runBuckBuild("//app:app")
        .assertSuccess("//app:app should still build even though lib/BUCK has been overwritten");

    workspace.verify(Paths.get("app"));
    workspace
        .runBuckBuild("//app:app")
        .assertSuccess("//app:app should still build with more fine-grained deps");
  }
}
