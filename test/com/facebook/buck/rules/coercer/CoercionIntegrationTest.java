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

package com.facebook.buck.rules.coercer;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.MoreStringsForTests;
import org.junit.Rule;
import org.junit.Test;

public class CoercionIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void errorProducesDependencyStack() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_project", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckBuild("//:b");
    assertThat(
        processResult.getStderr(),
        MoreStringsForTests.containsIgnoringPlatformNewlines(
            "cannot coerce '1' to com.google.common.collect.ImmutableList<com.facebook.buck.core.sourcepath.SourcePath>\n"
                + "    At //:a\n"
                + "    At //:b"));
  }
}
