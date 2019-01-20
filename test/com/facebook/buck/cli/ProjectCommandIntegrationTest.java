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

package com.facebook.buck.cli;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ProjectCommandIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testCommandFailsWithUnknownIde() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_project", temporaryFolder);
    workspace.setUp();

    String error = workspace.runBuckCommand("project", "--ide", "ide").getStderr();
    assertTrue(error.contains("BAD ARGUMENTS: Unknown IDE: ide."));
  }
}
