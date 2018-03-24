/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.python;

import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class PythonLibraryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  public ProjectWorkspace workspace;

  @Test
  public void excludeDepsFromOmnibus() throws Exception {
    assumeThat(Platform.detect(), Matchers.not(Matchers.is(Platform.WINDOWS)));

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "exclude_deps_from_merged_linking", tmp);
    workspace.setUp();

    workspace.runBuckBuild("-c", "python.native_link_strategy=merged", "//:bin");
  }
}
