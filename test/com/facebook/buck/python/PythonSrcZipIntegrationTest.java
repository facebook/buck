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

package com.facebook.buck.python;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Properties;

public class PythonSrcZipIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();
  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    Properties props = System.getProperties();
    props.setProperty(
        "buck.path_to_python_test_main",
        Paths.get("src/com/facebook/buck/python/__test_main__.py").toAbsolutePath().toString());
    props.setProperty(
        "buck.path_to_pex",
        Paths.get("src/com/facebook/buck/python/pex.py").toAbsolutePath().toString());

    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "src_zip", tmp);
    workspace.setUp();
  }

  @Test
  public void testDependingOnSrcZipWorks() throws IOException {
    // This test should pass.
    ProcessResult result1 = workspace.runBuckCommand("test", "//:test");
    result1.assertSuccess();
  }

}
