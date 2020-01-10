/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.example;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class MultipleOutputsIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void canBuildNamedOutputGroup() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "named_output_groups", tmp).setUp();

    Path result = workspace.buildAndReturnOutput("//:sushi[ika]");
    assertEquals("ika.txt", result.getFileName().toString());
    assertEquals("ika " + System.lineSeparator(), workspace.getFileContents(result));
  }

  @Test
  public void namedOutputsCanBeUsedInSrcOfAnyRule() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "named_output_groups", tmp).setUp();

    Path result = workspace.buildAndReturnOutput("//:unagi");
    assertEquals("yummy.txt", result.getFileName().toString());
    assertEquals("unagi " + System.lineSeparator(), workspace.getFileContents(result));
  }

  @Test
  public void namedOutputsCanBeUsedInSrcsOfAnyRule() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "named_output_groups", tmp).setUp();

    Path result = workspace.buildAndReturnOutput("//:i_ate_it[out.txt]");
    assertEquals("out.txt", result.getFileName().toString());
    assertEquals(
        "I ate maguro yellowtail unagi",
        workspace.getFileContents(result).replace(System.lineSeparator(), "").trim());

    result = workspace.buildAndReturnOutput("//:i_ate_ika[delicious.txt]");
    assertEquals("delicious.txt", result.getFileName().toString());
    assertEquals(
        "I ate ika", workspace.getFileContents(result).replace(System.lineSeparator(), "").trim());
  }

  @Test
  public void showOutputsReturnsCorrectPath() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "named_output_groups", tmp).setUp();
    String targetWithLabel = "//:sushi[maguro]";
    Path expectedPath =
        BuildTargetPaths.getGenPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance("//:sushi"),
                "%s__")
            .resolve("maguro.txt");

    ProcessResult result =
        workspace.runBuckBuild(targetWithLabel, "--show-outputs").assertSuccess();
    assertEquals(String.format("%s %s", targetWithLabel, expectedPath), result.getStdout().trim());
  }
}
