/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import java.io.IOException;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class NoJavaFilesIntegrationTest {
  private static final String TEST_TARGET = "//:not_java";

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_library_not_java", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testJavaLibraryWithNoJavaFilesFailsGracefully() {
    ProcessResult result = workspace.runBuckBuild(TEST_TARGET);
    result.assertExitCode(ExitCode.BUILD_ERROR);
    assertTrue(result.getStderr().contains(Jsr199JavacInvocation.NO_JAVA_FILES_ERROR_MESSAGE));
  }

  @Test
  @Parameters({"source", "source_only"})
  public void testJavaSourceAbiWithNoJavaFilesFailsGracefully(String abiGenerationMode) {
    ProcessResult result =
        workspace.runBuckBuild(TEST_TARGET, "-c", "java.abi_generation_mode=" + abiGenerationMode);
    result.assertExitCode(ExitCode.BUILD_ERROR);
    assertThat(
        result.getStderr(),
        stringContainsInOrder(
            Jsr199JavacInvocation.NO_JAVA_FILES_ERROR_MESSAGE,
            JavaAbis.SOURCE_ABI_FLAVOR.toString()));
  }
}
