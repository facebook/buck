/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Files;

public class GenruleIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Rule
  public ExpectedException exception = ExpectedException.none();

  // When these tests fail, the failures contain buck output that is easy to confuse with the output
  // of the instance of buck that's running the test. This prepends each line with "> ".
  private String quoteOutput(String output) {
    output = output.trim();
    output = "> " + output;
    output = output.replace("\n", "\n> ");
    return output;
  }

  @Test
  public void testIfCommandExitsZeroThenGenruleFails() throws IOException {
    assumeTrue("This genrule uses the 'bash' argument, which is not supported on Windows. ",
        Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "genrule_failing_command", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//:fail", "--verbose", "10");
    buildResult.assertFailure();

    /* We want to make sure we failed for the right reason. The expected should contain something
     * like the following:
     *
     * BUILD FAILED: //:fail failed with exit code 1:
     * (cd /tmp/junit12345/buck-out/gen/fail__srcs && /bin/bash -e -c 'false; echo >&2 hi')
     *
     * We should match all that, except for the specific temp dir.
     */

    // "(?s)" enables multiline matching for ".*". Parens have to be escaped.
    String outputPattern =
        "(?s).*BUILD FAILED: //:fail failed with exit code 1:(?s).*" +
        "\\(cd .*/buck-out/gen/fail__srcs && /bin/bash -e -c 'false; echo >&2 hi'\\)(?s).*";

    assertTrue(
        "Unexpected output:\n" + quoteOutput(buildResult.getStderr()),
        buildResult.getStderr().matches(outputPattern));
  }

  @Test
  public void genruleWithEmptyOutParameterFails() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "genrule_empty_out", temporaryFolder);
    workspace.setUp();

    exception.expect(HumanReadableException.class);
    exception.expectMessage(
        "The 'out' parameter of genrule //:genrule is '', which is not a valid file name.");

    workspace.runBuckCommand("build", "//:genrule");
  }

  @Test
  public void genruleWithAbsoluteOutParameterFails() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "genrule_absolute_out", temporaryFolder);
    workspace.setUp();

    exception.expect(HumanReadableException.class);
    exception.expectMessage(
        "The 'out' parameter of genrule //:genrule is '/tmp/file', " +
            "which is not a valid file name.");

    workspace.runBuckCommand("build", "//:genrule");
  }

  @Test
  public void genruleDirectoryOutput() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "genrule_directory_output", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:mkdir").assertSuccess();

    assertThat(Files.isDirectory(workspace.resolve("buck-out/gen/directory")), equalTo(true));
    assertThat(
        workspace.getFileContents("buck-out/gen/directory/file"),
        equalTo("something\n"));

    workspace.runBuckCommand("clean").assertSuccess();

    assertThat(Files.isDirectory(workspace.resolve("buck-out/gen")), equalTo(false));

    // Retrieving the genrule output from the local cache should recreate the directory contents.
    workspace.runBuckCommand("build", "//:mkdir").assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache("//:mkdir");
    assertThat(Files.isDirectory(workspace.resolve("buck-out/gen/directory")), equalTo(true));
    assertThat(
        workspace.getFileContents("buck-out/gen/directory/file"),
        equalTo("something\n"));
  }

  @Test
  public void genruleDirectoryOutputIsCleanedBeforeBuildAndCacheFetch() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "genrule_directory_output_cleaned", temporaryFolder);
    workspace.setUp();

    workspace.copyFile("BUCK.1", "BUCK");
    workspace.runBuckCommand("build", "//:mkdir_another").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:mkdir_another");
    assertTrue(
        "mkdir_another should be built",
        Files.isRegularFile(workspace.resolve("buck-out/gen/another_directory/file")));

    workspace.runBuckCommand("build", "//:mkdir").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:mkdir");
    assertTrue(
        "BUCK.1 should create its output",
        Files.isRegularFile(workspace.resolve("buck-out/gen/directory/one")));
    assertFalse(
        "BUCK.1 should not touch the output of BUCK.2",
        Files.isRegularFile(workspace.resolve("buck-out/gen/directory/two")));
    assertTrue(
        "output of mkdir_another should still exist",
        Files.isRegularFile(workspace.resolve("buck-out/gen/another_directory/file")));

    workspace.copyFile("BUCK.2", "BUCK");
    workspace.runBuckCommand("build", "//:mkdir").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:mkdir");
    assertFalse(
        "Output of BUCK.1 should be deleted before output of BUCK.2 is built",
        Files.isRegularFile(workspace.resolve("buck-out/gen/directory/one")));
    assertTrue(
        "BUCK.2 should create its output",
        Files.isRegularFile(workspace.resolve("buck-out/gen/directory/two")));
    assertTrue(
        "output of mkdir_another should still exist",
        Files.isRegularFile(workspace.resolve("buck-out/gen/another_directory/file")));

    workspace.copyFile("BUCK.1", "BUCK");
    workspace.runBuckCommand("build", "//:mkdir").assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache("//:mkdir");
    assertTrue(
        "Output of BUCK.1 should be fetched from the cache",
        Files.isRegularFile(workspace.resolve("buck-out/gen/directory/one")));
    assertFalse(
        "Output of BUCK.2 should be deleted before output of BUCK.1 is fetched from cache",
        Files.isRegularFile(workspace.resolve("buck-out/gen/directory/two")));
    assertTrue(
        "output of mkdir_another should still exist",
        Files.isRegularFile(workspace.resolve("buck-out/gen/another_directory/file")));
  }

  @Test
  public void genruleDirectorySourcePath() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "genrule_directory_source_path", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//:cpdir");
    buildResult.assertSuccess();

    assertThat(Files.isDirectory(workspace.resolve("buck-out/gen/copy")), equalTo(true));
    assertThat(Files.isRegularFile(workspace.resolve("buck-out/gen/copy/hello")), equalTo(true));
  }

}
