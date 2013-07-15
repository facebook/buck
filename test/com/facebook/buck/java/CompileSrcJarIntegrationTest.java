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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

public class CompileSrcJarIntegrationTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  @Ignore("TODO(mbolin): Update JavacInMemoryStep to make this test pass.")
  public void testJavaLibraryRuleAcceptsJarFileOfJavaSourceCodeAsInput() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "src_jar", tmp);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//:lib");
    assertEquals("Successful build should exit with 0.", 0, buildResult.getExitCode());
  }
}
