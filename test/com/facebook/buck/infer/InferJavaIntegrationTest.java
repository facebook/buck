/*
 * Copyright 2019-present Facebook, Inc.
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
  public void inferJavaLibraryBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "several_libraries", tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:j#nullsafe");
    JsonNode issues = ObjectMappers.READER.readTree(workspace.getFileContents(output));
    assertEquals("ERADICATE_RETURN_NOT_NULLABLE", issues.path(0).path("bug_type").asText());
  }

  @Test
  public void inferJavaEmptySourcesBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "several_libraries", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:empty#nullsafe").assertSuccess();
  }

  @Test
  @Ignore(
      "TODO: `buck test` fails with unknown android-platform-target toolchain (but works in Idea)")
  public void inferAndroidLibraryBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "several_libraries", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//:a#nullsafe");
    result.assertSuccess();
  }
}
