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

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class AuditInputCommandIntegrationTest {

  private String expectedStdout =
      Platform.detect() == Platform.WINDOWS ? "stdout-windows" : "stdout";
  private String expectedStdoutJson =
      Platform.detect() == Platform.WINDOWS ? "stdout-windows.json" : "stdout.json";

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testBuckAuditInputAppleResourceDirs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_input", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "input", "//example:foo");
    result.assertSuccess();
    assertEquals(workspace.getFileContents(expectedStdout), result.getStdout());
  }

  @Test
  public void testBuckAuditInputJsonAppleResourceDirs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_input", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule in JSON format.
    ProcessResult result = workspace.runBuckCommand("audit", "input", "//example:foo", "--json");
    result.assertSuccess();
    assertEquals(workspace.getFileContents(expectedStdoutJson), result.getStdout());
  }

  @Test
  public void testBuckAuditInputExportFileWithoutSrc() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_input_no_src", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "input", "//example:foo.plist");
    result.assertSuccess();
    assertEquals(workspace.getFileContents(expectedStdout), result.getStdout());
  }
}
