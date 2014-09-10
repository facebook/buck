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
package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ProcessExecutor;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class CopyResourcesStepIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testGeneratedResourceIsAlongsideClassFiles()
      throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "generated_resources", temporaryFolder);
    workspace.setUp();

    File exampleJar = workspace.buildAndReturnOutput("//java/com/example:example");
    ZipInspector exampleInspector = new ZipInspector(exampleJar);
    exampleInspector.assertFileExists("com/example/HelloWorld.class");
    exampleInspector.assertFileExists("com/example/res/helloworld.txt");

    File helloWorldJar = workspace.buildAndReturnOutput("//java/com/example:HelloWorld");
    ProcessExecutor.Result result = workspace.runJar(helloWorldJar);
    assertEquals("hello world\n", result.getStdout().get());
    assertEquals("", result.getStderr().get());
  }
}
