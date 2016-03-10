/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class WorkerToolRuleIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "worker_tool_test",
        tmpFolder);
    workspace.setUp();
  }

  /**
   * This test builds three genrules simultaneously which each use a worker macro. //:test1 and
   * //:test2 both reference the same worker_tool, so they will both communicate with the same
   * external process, while //:test3 will communicate with a separate process since it references
   * a separate worker_tool.
   *
   * @throws IOException
   */
  @Test
  public void testGenrulesThatUseWorkerMacros() throws IOException {
    workspace.runBuckBuild("//:test1", "//:test2", "//:test3").assertSuccess();
    workspace.verify();
  }
}
