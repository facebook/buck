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

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class ExternalJavacIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void whenExternalJavacIsSetCompilationSucceeds()
    throws IOException, InterruptedException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "external_javac", tmp);

    workspace.setUp();

    File javac = workspace.getFile("javac.sh");
    javac.setExecutable(true);

    workspace.replaceFileContents(".buckconfig", "@JAVAC@", javac.getAbsolutePath());
    workspace.runBuckCommand("build", "example").assertSuccess();
  }

  @Test
  @Ignore("Disabled due to badness t4689997")
  public void whenExternalSrcZipUsedCompilationSucceeds()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "external_javac_src_zip", tmp);

    workspace.setUp();

    File javac = workspace.getFile("javac.sh");
    javac.setExecutable(true);

    workspace.replaceFileContents(".buckconfig", "@JAVAC@", javac.getAbsolutePath());

    workspace.runBuckCommand("build", "//:lib", "-v", "2").assertSuccess();
  }

  @Test
  public void whenExternalJavacFailsOutputIsInFailureMessage()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "external_javac", tmp);
    workspace.setUp();

    File error = workspace.getFile("error.sh");
    error.setExecutable(true);

    workspace.replaceFileContents(".buckconfig", "@JAVAC@", error.getAbsolutePath());
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "example");

    assertThat(
        "Failure should have been due to external javac.", result.getStderr(),
        Matchers.containsString("error compiling"));
    assertThat(
        "Expected exit code should have been in failure message.", result.getStderr(),
        Matchers.containsString("42"));
  }

  @Test
  public void whenBuckdUsesExternalJavacThenClientEnvironmentUsed() throws IOException {
    final ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "external_javac", tmp);
    workspace.setUp();

    File javac = workspace.getFile("check_env.sh");
    javac.setExecutable(true);

    workspace.replaceFileContents(".buckconfig", "@JAVAC@", javac.getAbsolutePath());
    workspace.runBuckdCommand(
        ImmutableMap.of("CHECK_THIS_VARIABLE", "1"),
        "build",
        "example")
        .assertSuccess();
  }

}
