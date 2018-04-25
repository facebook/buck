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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class AuditCellCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testBuckCell() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/primary", tmp.newFolder());
    workspace.setUp();
    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/secondary", tmp.newFolder());
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));

    ProcessResult result = workspace.runBuckCommand("audit", "cell");
    result.assertSuccess();

    // Remove trailing newline from stdout before passing to Splitter.
    String stdout = result.getStdout();
    assertTrue(stdout.endsWith("\n"));
    stdout = stdout.substring(0, stdout.length() - 1);

    List<String> cells = Splitter.on('\n').splitToList(stdout);
    assertEquals("Cells that appear in both .buckconfig should appear.", 1, cells.size());
    assertEquals(
        ImmutableSet.of(String.format("secondary: %s", secondary.getDestPath())),
        ImmutableSet.copyOf(cells));
  }

  @Test
  public void testBuckCellJsonOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/primary", tmp.newFolder());
    workspace.setUp();
    ProjectWorkspace secondary =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "crosscell_file_watching/secondary", tmp.newFolder());
    secondary.setUp();
    TestDataHelper.overrideBuckconfig(
        workspace,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of("secondary", secondary.getPath(".").normalize().toString())));

    ProcessResult result = workspace.runBuckCommand("audit", "cell", "--json");
    result.assertSuccess();

    Map<String, String> cellMap =
        ObjectMappers.readValue(result.getStdout(), new TypeReference<Map<String, String>>() {});
    assertEquals("Cells that appear in both .buckconfig should appear.", 1, cellMap.size());
    assertTrue("Expected to find secondary in the map", cellMap.containsKey("secondary"));
    assertEquals(secondary.getDestPath().toString(), cellMap.get("secondary"));
  }
}
