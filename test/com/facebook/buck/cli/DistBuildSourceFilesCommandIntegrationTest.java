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

package com.facebook.buck.cli;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class DistBuildSourceFilesCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void runCommandAndCheckOutputFile() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "dist_build_source_files_example", tmp);
    workspace.setUp();
    Path outputFile = tmp.newFile();
    workspace
        .runBuckCommand(
            "distbuild", "sourcefiles", "--output-file=" + outputFile.toAbsolutePath(), ":libA")
        .assertSuccess();
    Assert.assertTrue(Files.exists(outputFile));
    List<String> lines = Files.readAllLines(outputFile);
    Collections.sort(lines);
    Assert.assertEquals(2, lines.size());
    Assert.assertTrue(lines.get(0).contains("A.java"));
    Assert.assertTrue(lines.get(1).contains("B.java"));
  }
}
