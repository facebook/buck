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

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class BuildTargetSourcePathAsSrcIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testNewGenfileIsIncludedInJar() throws IOException {
    final Charset charsetForTest = StandardCharsets.UTF_8;
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "build_rule_source_path_as_src_test", tmp);
    workspace.setUp();

    // The test should pass out of the box.
    ProcessResult result = workspace.runBuckCommand("test", "//:test");
    result.assertSuccess();

    // Edit the test so it should fail and then make sure that it fails.
    Path testFile = workspace.getPath("resource.base.txt");
    Files.write(testFile, "Different text".getBytes(charsetForTest));
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test");

    workspace.getBuildLog().assertTargetBuiltLocally("//:library");
    result2.assertTestFailure();
    assertThat("`buck test` should fail because testStringFromGenfile() failed.",
        result2.getStderr(),
        containsString("FAILURE com.example.LameTest testStringFromGenfile"));
  }

}
