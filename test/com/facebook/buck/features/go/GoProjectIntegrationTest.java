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

package com.facebook.buck.features.go;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class GoProjectIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void ensureGoIsAvailable() throws IOException {
    GoAssumptions.assumeGoCompilerAvailable();
  }

  @Test
  public void testBuckProjectCopyGeneratedGoCode() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "go_project_with_generated_code", temporaryFolder.newFolder());
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("project");
    result.assertSuccess("buck project should exit cleanly");

    assertTrue(workspace.resolve("vendor/a/b1.go").toFile().exists());
    assertTrue(workspace.resolve("vendor/a/b2.go").toFile().exists());
  }

  @Test
  public void testBuckProjectPurgeExistingVendor() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "go_project_with_existing_vendor", temporaryFolder.newFolder());
    workspace.setUp();
    assertTrue(workspace.resolve("vendor/a/b0.go").toFile().exists());
    assertTrue(workspace.resolve("vendor/a/b/b.go").toFile().exists());
    ProcessResult result = workspace.runBuckCommand("project");
    result.assertSuccess("buck project should exit cleanly");

    assertFalse(workspace.resolve("vendor/a/b0.go").toFile().exists());
    assertTrue(workspace.resolve("vendor/a/b1.go").toFile().exists());
    assertTrue(workspace.resolve("vendor/a/b2.go").toFile().exists());
    assertTrue(workspace.resolve("vendor/a/c1.go").toFile().exists());
    assertTrue(workspace.resolve("vendor/a/c2.go").toFile().exists());
    assertTrue(workspace.resolve("vendor/a/b/b.go").toFile().exists());
  }
}
