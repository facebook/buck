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

package com.facebook.buck.shell;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ShBinaryRuleIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testTrivialShBinaryRule() throws IOException {
    // sh_binary is not available on Windows. Ignore this test on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "sh_binary_trivial", temporaryFolder);
    workspace.setUp();

    Path outputFile = workspace.buildAndReturnOutput("//:run_example");

    // Verify contents of example_out.txt
    String output = new String(Files.readAllBytes(outputFile), US_ASCII);
    assertEquals("arg1\narg2\n", output);
  }

  @Test
  public void testExecutableFromCache() throws IOException, InterruptedException {
    // sh_binary is not available on Windows. Ignore this test on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "sh_binary_with_caching", temporaryFolder);
    workspace.setUp();

    // First build only the sh_binary rule itself.
    ProcessResult buildResult = workspace.runBuckCommand("build", "//:example_sh", "-v", "2");
    buildResult.assertSuccess();

    // Make sure the sh_binary output is executable to begin with.
    String outputPath = "buck-out/gen/__example_sh__/example_sh.sh";
    Path output = workspace.getPath(outputPath);
    assertTrue("Output file should be written to '" + outputPath + "'.", Files.exists(output));
    assertTrue("Output file must be executable.", Files.isExecutable(output));

    // Now delete the buck-out directory (but not buck-cache).
    Path buckOutDir = workspace.getPath("buck-out");
    MoreFiles.deleteRecursivelyIfExists(buckOutDir);

    // Now run the genrule that depends on the sh_binary above. This will force buck to fetch the
    // sh_binary output from cache. If the executable flag is lost somewhere along the way, this
    // will fail.
    buildResult = workspace.runBuckCommand("build", "//:run_example", "-v", "2");
    buildResult.assertSuccess("Build failed when rerunning sh_binary from cache.");

    // In addition to running the build, explicitly check that the output file is still executable.
    assertTrue(
        "Output file must be retrieved from cache at '" + outputPath + ".",
        Files.exists(output));
    assertTrue("Output file retrieved from cache must be executable.", Files.isExecutable(output));
  }

  @Test
  public void testShBinaryWithResources() throws IOException {
    // sh_binary is not available on Windows. Ignore this test on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "sh_binary_with_resources", temporaryFolder);
    workspace.setUp();

    Path outputFile = workspace.buildAndReturnOutput("//app:create_output_using_node");

    // Verify contents of output.txt
    List<String> lines = Files.readAllLines(outputFile, US_ASCII);
    ExecutionEnvironment executionEnvironment =
        new DefaultExecutionEnvironment(
            new FakeProcessExecutor(),
            ImmutableMap.copyOf(System.getenv()),
            System.getProperties());
    String expectedPlatform = executionEnvironment.getPlatform().getPrintableName();
    assertEquals(expectedPlatform, lines.get(0));
    assertEquals("arg1 arg2", lines.get(1));
  }

  @Test
  public void testShBinaryCannotOverwriteResource() throws IOException {
    // sh_binary is not available on Windows. Ignore this test on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "sh_binary_with_overwrite_violation", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//:overwrite");
    buildResult.assertFailure();

    assertThat(buildResult.getStderr(), containsString("/overwrite.sh: Permission denied"));
  }
}
