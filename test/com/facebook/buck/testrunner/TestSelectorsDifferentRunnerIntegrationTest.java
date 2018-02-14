/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.testrunner;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class TestSelectorsDifferentRunnerIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void shouldSelectOneTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "test_selectors_annotated_with_runwith", temporaryFolder);
    workspace.setUp();

    Path file = workspace.getPath("AnotherRunnerLogger.log");
    assertFalse("Log file shouldn't exist yet", Files.exists(file));

    ProcessResult result =
        workspace.runBuckCommand("test", "//test:broken", "--test-selectors", "TestA");
    assertThat(
        "We were expecting TestA to run!",
        result.getStderr(),
        containsString("com.example.broken.TestA"));
    assertThat(
        "We were *not* expecting TestB to run; it should be filtered out!",
        result.getStderr(),
        not(containsString("com.example.broken.TestB")));

    assertTrue("Log file should have been created by our custom runner!", Files.exists(file));
  }
}
