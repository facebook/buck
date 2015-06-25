/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

public class GenruleDepsIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testUpdatingJarBuildByGenruleAffectDependentRebuild() throws IOException {
    final Charset charsetForTest = Charsets.UTF_8;
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "genrule_test", tmp);
    workspace.setUp();

    // The test should pass out of the box.
    ProcessResult result = workspace.runBuckCommand("test", "//:test");
    result.assertSuccess();

    // Edit the test so it should fail and then make sure that it fails.
    File testFile = workspace.getFile("resource.base.txt");
    Files.write("Different text", testFile, charsetForTest);
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test");
    result2.assertTestFailure();
    assertThat("`buck test` should fail because testBasicAssertion() failed.",
        result2.getStderr(),
        containsString("FAILURE com.example.LameTest testBasicAssertion"));
  }

}
