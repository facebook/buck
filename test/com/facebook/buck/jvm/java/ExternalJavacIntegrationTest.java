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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class ExternalJavacIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void whenExternalJavacIsSetCompilationSucceeds() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "external_javac", tmp);

    workspace.setUp();

    Path javac = workspace.getPath("javac.sh");
    MostFiles.makeExecutable(javac);

    workspace.replaceFileContents(".buckconfig", "@JAVAC@", javac.toAbsolutePath().toString());
    workspace.runBuckCommand("build", "example").assertSuccess();
  }

  @Test
  public void whenExternalSrcZipUsedCompilationSucceeds() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "external_javac_src_zip", tmp);

    workspace.setUp();

    Path javac = workspace.getPath("javac.sh");
    MostFiles.makeExecutable(javac);

    workspace.replaceFileContents(".buckconfig", "@JAVAC@", javac.toAbsolutePath().toString());

    workspace.runBuckCommand("build", "//:lib", "-v", "2").assertSuccess();
  }

  @Test(timeout = 180000)
  public void whenExternalSrcZipUsedBuildingBinarySucceeds() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "external_javac", tmp);

    workspace.setUp();

    workspace.replaceFileContents(".buckconfig", "@JAVAC@", "//:real-javac.sh");

    workspace
        .runBuckCommand("build", "-c", "cache.mode=dir", "//java/com/example:example_binary")
        .assertSuccess();

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    workspace.writeContentsToPath("int foo() {  return 1; }", "java/com/example/foo.c");

    workspace
        .runBuckCommand("build", "-c", "cache.mode=dir", "//java/com/example:example_binary")
        .assertSuccess();
  }

  @Test
  public void whenExternalJavacFailsOutputIsInFailureMessage() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "external_javac", tmp);
    workspace.setUp();

    Path error = workspace.getPath("error.sh");
    MostFiles.makeExecutable(error);

    workspace.replaceFileContents(".buckconfig", "@JAVAC@", error.toAbsolutePath().toString());
    ProcessResult result = workspace.runBuckCommand("build", "example");

    assertThat(
        "Failure should have been due to external javac.",
        result.getStderr(),
        Matchers.containsString("error compiling"));
    assertThat(
        "Expected exit code should have been in failure message.",
        result.getStderr(),
        Matchers.containsString("42"));
  }

  @Test
  public void whenBuckdUsesExternalJavacThenClientEnvironmentUsed() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "external_javac", tmp);
    workspace.setUp();

    Path javac = workspace.getPath("check_env.sh");
    MostFiles.makeExecutable(javac);

    workspace.replaceFileContents(".buckconfig", "@JAVAC@", javac.toAbsolutePath().toString());
    workspace
        .runBuckdCommand(
            ImmutableMap.of(
                "CHECK_THIS_VARIABLE",
                "1",
                "PATH",
                EnvVariablesProvider.getSystemEnv().get("PATH")),
            "build",
            "example")
        .assertSuccess();
  }
}
