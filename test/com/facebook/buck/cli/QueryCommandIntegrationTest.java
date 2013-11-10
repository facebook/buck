/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class QueryCommandIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testBuckAuditRules() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "dependency_project", tmp);
    workspace.setUp();

    // Conduct a standard path query.
    ProcessResult result1 = workspace.runBuckCommand("query", "//modules/tip:tip -> //libs:guava");
    result1.assertExitCode(0);
    assertEquals(
        "//modules/tip:tip -> //libs:guava\n",
        result1.getStdout());

    // Standard dependency query.
    ProcessResult result2 = workspace.runBuckCommand("query", "//modules/tip:tip -2>");
    result2.assertExitCode(0);
    // We may need to make these asserts resilient to changes in line ordering as well.
    assertEquals(
        "//modules/tip:tip\n"
            + "//libs:guava\n"
            + "//libs:jsr305\n"
            + "//modules/dep1:dep1\n",
        result2.getStdout());

    // Malformed query.
    try {
      workspace.runBuckCommand("query", "//modules/tip:tip -x>");
      fail("Should have thrown exception on malformed query.");
    } catch (HumanReadableException e) {
      assertEquals("Invalid query string: //modules/tip:tip -x>.", e.getMessage());
    }

    // Invalid target in query.
    try {
      workspace.runBuckCommand("query", "//modules/tip:tip -> //:x");
      fail("Should have thrown exception on invalid target.");
    } catch (HumanReadableException e) {
      assertEquals("Unknown build target: //:x.", e.getMessage());
    }
  }
}
