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

package com.facebook.buck.shell;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class QueryDepthIterationIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws Exception {

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
                this, "query_depth_iteration", temporaryFolder)
            .setUp();
  }

  @Test
  public void testWeCanTraverseAlongJustNormalDepsEdges() throws Exception {
    expectGenruleOutput(
        ":print_deps_not_provided", ImmutableList.of("//:Dep", "//:ShouldStay", "//:Top"));
  }

  private void expectGenruleOutput(String genrule, List<String> expectedOutputs) throws Exception {
    ProcessResult buildResult = workspace.runBuckCommand("build", genrule);
    buildResult.assertSuccess();

    String outputFileContents = workspace.getFileContents(getOutputFile(genrule));
    List<String> actualOutput =
        Arrays.stream(outputFileContents.split("\\s"))
            .map(String::trim)
            .collect(Collectors.toList());
    assertEquals(expectedOutputs, actualOutput);
  }

  private Path getOutputFile(String targetName) {
    try {
      ProcessResult buildResult =
          workspace.runBuckCommand("targets", targetName, "--show-full-output", "--json");
      buildResult.assertSuccess();
      JsonNode jsonNode = ObjectMappers.READER.readTree(buildResult.getStdout()).get(0);
      assert jsonNode.has("buck.outputPath");
      return Paths.get(jsonNode.get("buck.outputPath").asText());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
