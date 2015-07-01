/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

/**
 * Integration tests for {@link TargetGraphHashing}.
 */
public class TargetGraphHashingIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void hashDoesNotChangeWhenResourcesRootFolderContentsChange() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "java_library_resources_root_target_hash", tmp);
    workspace.setUp();

    String expectedHash = workspace.runBuckCommand(
        "targets",
        "--show-target-hash",
        "//:empty").getStdout();
    workspace.writeContentsToPath("some content", "somefile.txt");
    String hash = workspace.runBuckCommand("targets", "--show-target-hash", "//:empty").getStdout();
    assertThat(hash, Matchers.equalTo(expectedHash));
  }
}
