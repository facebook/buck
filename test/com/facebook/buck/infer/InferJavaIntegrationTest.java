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

package com.facebook.buck.infer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class InferJavaIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void ensureInferIsAvailable() {
    InferAssumptions.assumeInferIsConfigured();
  }

  @Test
  public void inferJavaLibrarySmokeTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "several_libraries", tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:java-smoke-test#nullsafe");
    JsonNode issues = ObjectMappers.READER.readTree(workspace.getFileContents(output));
    assertEquals("ERADICATE_RETURN_NOT_NULLABLE", issues.path(0).path("bug_type").asText());
  }

  @Test
  public void inferJavaEmptySourcesTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "several_libraries", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:empty-srcs-test#nullsafe").assertSuccess();
  }

  @Test
  public void inferJavaProvidedDepTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "several_libraries", tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:java-provided-dep-test#nullsafe");
    JsonNode issues = ObjectMappers.READER.readTree(workspace.getFileContents(output));
    assertEquals(0, issues.size());
  }

  @Test
  public void inferJavaExportedProvidedDepTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "several_libraries", tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:java-exported-provided-dep-test#nullsafe");
    JsonNode issues = ObjectMappers.READER.readTree(workspace.getFileContents(output));
    assertEquals(0, issues.size());
  }

  @Test
  public void inferJavaFromDistTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "dist", tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:l1#nullsafe");
    String content = workspace.getFileContents(output);
    assertEquals("fake infer results\n", content);
  }

  @Test
  @Ignore(
      "TODO: `buck test` fails with unknown android-platform-target toolchain (but works in Idea)")
  public void inferAndroidLibrarySmokeTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "several_libraries", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:android-smoke-test#nullsafe");
    result.assertSuccess();
  }
}
