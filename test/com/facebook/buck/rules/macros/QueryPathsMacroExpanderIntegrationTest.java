/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Escaper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class QueryPathsMacroExpanderIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void buildRulesThatAreReturnedAreConvertedToTheirOutputPaths() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query-paths", tmp);
    workspace.setUp();

    Path result = workspace.buildAndReturnOutput("//:rules");

    // Remove the newline added by echo from the result
    String seen = new String(Files.readAllBytes(result), UTF_8).trim();

    Path expected = workspace.buildAndReturnOutput("//:beta");
    assertTrue(expected.isAbsolute());
    assertEquals(expected.toString(), seen);
  }

  @Test
  public void macroResultsCanBeFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "query-paths", tmp);
    workspace.setUp();

    Path result = workspace.buildAndReturnOutput("//:files");

    // Remove the newline added by echo from the result
    String seen = new String(Files.readAllBytes(result), UTF_8).trim();

    // Read in the file list
    Path filelistPath = Paths.get(seen.substring(1));
    String filelist = new String(Files.readAllBytes(filelistPath), UTF_8);

    Path beta = workspace.getPath("beta.txt");
    Path spaces = workspace.getPath("file with spaces.txt");
    assertTrue(beta.isAbsolute());
    assertTrue(filelist.contains(beta.toString()));
    assertTrue(filelist.contains(Escaper.escapeAsShellString(spaces.toString())));
  }
}
