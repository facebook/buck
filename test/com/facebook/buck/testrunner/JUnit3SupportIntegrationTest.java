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

package com.facebook.buck.testrunner;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JUnit3SupportIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setupWorkspace() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "junit3_tests", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void testShouldRun() throws IOException {
    Set<String> expectedPassingTests = ImmutableSet.of("com.example.TestA", "com.example.TestB");
    String[] args = new String[] {"test", "--all"};
    ProcessResult result = workspace.runBuckCommand(args);
    String[] lines = result.getStderr().split("\n");
    ImmutableSet.Builder<String> actualPassingTestsBuilder = new ImmutableSet.Builder<>();
    for (String line : lines) {
      String[] tokens = line.split("\\s+");
      String testName = tokens[tokens.length - 1];
      if (testName.startsWith("com.example")) {
        assertThat(tokens[0], equalTo("PASS"));
        actualPassingTestsBuilder.add(testName);
      }
    }
    ImmutableSet<String> actualPassingTests = actualPassingTestsBuilder.build();
    assertEquals("Expected tests were run (and passed)", expectedPassingTests, actualPassingTests);
  }
}
