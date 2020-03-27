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

package com.facebook.buck.cli.cquery;

import static com.facebook.buck.util.MoreStringsForTests.normalizeNewlines;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.OutputHelper;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class ConfiguredQueryCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  /**
   * Asserts that the result succeeded and that the lines printed to stdout are the same as those in
   * the specified file. Note that sort order is not guaranteed by {@code buck query} unless it is
   * specified explicitly via {@code --sort-output}.
   */
  private void assertLinesMatch(
      String expectedOutputFile, ProcessResult result, ProjectWorkspace workspace)
      throws IOException {
    result.assertSuccess();

    // All lines in expected output files are sorted so sort the output from `buck query` before
    // comparing. Although query/--sort-output claims to sort labels by default, this does not
    // appear to be honored, in practice.
    assertEquals(
        normalizeNewlines(workspace.getFileContents(expectedOutputFile)),
        OutputHelper.normalizeOutputLines(normalizeNewlines(result.getStdout())));
  }

  @Test
  public void basicTargetPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("cquery", "//lib:foo");

    result.assertSuccess();
    // TODO(srice): We shouldn't expect it to print a readable name, but until we know what the hash
    // is going to be it doesn't matter what we put here.
    assertEquals("//lib:foo (//config/platform:ios)", result.getStdout().trim());
  }

  @Test
  public void configFunctionConfiguresTargetForSpecificPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("cquery", "config(//lib:foo, //config/platform:tvos)");
    assertEquals("//lib:foo (//config/platform:tvos)", result.getStdout().trim());
  }

  @Test
  @Ignore
  public void targetUniverseChangesOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult tvOSResult =
        workspace.runBuckCommand("cquery", "//lib:foo", "--target-universe", "//bin:tvos-bin");
    assertEquals("//lib:foo (//config/platform:tvos)", tvOSResult.getStdout());

    ProcessResult macOSResult =
        workspace.runBuckCommand("cquery", "//lib:foo", "--target-universe", "//bin:mac-bin");
    assertEquals("//lib:foo (//config/platform:macos)", macOSResult.getStdout().trim());
  }

  @Test
  @Ignore
  public void ownerForFileWithOwnerThatsOutsideTargetUniverseReturnsNothing() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    // Even though `lib/maconly.m` is unconditionally included as a source of `//lib:maconly`, that
    // target is outside the target universe and therefore the query should return no results.
    ProcessResult result =
        workspace.runBuckCommand(
            "cquery", "owner(lib/maconly.m)", "--target-universe", "//bin:tvos-bin");
    assertEquals("", result.getStdout().trim());
  }

  @Test
  @Ignore
  public void multipleLinesPrintedForOneTargetInMulitpleConfigurations() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery",
            "set(config(//lib:foo, //config/platform:ios) config(//lib:foo, //config/platform:macos))");
    assertLinesMatch(
        "stdout-multiple-lines-printed-for-one-target-in-multiple-configurations",
        result,
        workspace);
  }

  @Test
  @Ignore
  public void twoTargetsInTargetUniverseCausesOverlapToBePrintedInBothConfigurations()
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_apple", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "cquery", "set(//lib/...)", "--target-universe", "//bin:ios-bin,//bin:tvos-bin");
    assertLinesMatch(
        "stdout-two-targets-in-target-universe-causes-overlap-to-be-printed-in-both-configurations",
        result,
        workspace);
  }
}
