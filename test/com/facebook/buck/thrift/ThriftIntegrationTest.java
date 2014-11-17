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

package com.facebook.buck.thrift;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ThriftWatcher;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class ThriftIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testThriftFileGen() throws IOException, InterruptedException {
    assumeTrue(ThriftWatcher.isThriftAvailable());
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "thrift_test", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild();
    result.assertSuccess();
    String testFile = workspace.getFileContents(
        "buck-out/gen/__thrift_thrifty#java_srcs__/gen-java/com/" +
        "facebook/fbtrace/constants/TestConstants.java");
    assertThat("Check to make sure java file is being generated",
        testFile,
        containsString("public class TestConstants {"));
    String testJar = workspace.getFileContents(
        "buck-out/gen/lib__thrifty#java__output/thrifty#java.jar");
    assertThat("Check to make sure .jar is being generated",
        testJar,
        containsString(".class"));
  }

}
