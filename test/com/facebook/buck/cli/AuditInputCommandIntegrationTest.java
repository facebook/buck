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

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class AuditInputCommandIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testBuckAuditInputAppleResourceDirs() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_input", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "input", "//example:foo");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout"), result.getStdout());
  }

  @Test
  public void testBuckAuditInputJsonAppleResourceDirs() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "audit_input", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule in JSON format.
    ProcessResult result = workspace.runBuckCommand("audit", "input", "//example:foo", "--json");
    result.assertSuccess();
    assertEquals(workspace.getFileContents("stdout.json"), result.getStdout());
  }
}
