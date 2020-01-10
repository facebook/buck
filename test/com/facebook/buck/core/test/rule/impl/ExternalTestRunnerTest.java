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

package com.facebook.buck.core.test.rule.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ExternalTestRunnerTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void externalRuleParses() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "test_with_external_runner", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:external_runner").assertSuccess();

    ProcessResult result = workspace.runBuckCommand("targets", "--json", "//:external_runner");
    JsonNode observed =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(result.getStdout()));
    JsonNode root = observed.get(0);
    assertEquals("external_runner", root.get("name").textValue());
    assertEquals(":runner_binary", root.get("binary").textValue());
  }

  @Test
  public void externalRunnerRuleShouldNotReturnOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "test_with_external_runner", temporaryFolder);
    workspace.setUp();
    expectedException.expect(AssertionError.class);
    expectedException.expectMessage("Target //:external_runner has no outputs.");

    workspace.buildAndReturnOutput("//:external_runner");
  }

  @Test
  public void externalRunnerRuleShouldPointToBinary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "test_with_external_runner", temporaryFolder);
    workspace.setUp();
    workspace.runBuckBuild("//:runner_library").assertSuccess();

    ProcessResult result =
        workspace.runBuckBuild("//:invalid_external_runner").assertExitCode(ExitCode.FATAL_GENERIC);
    assertTrue(
        result
            .getStderr()
            .contains("external_test_runner should have one dependency that points to a binary"));
  }
}
