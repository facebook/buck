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

package com.facebook.buck.cli.uquery;

import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertJSONOutputMatchesFileContents;
import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertOutputMatches;
import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertOutputMatchesExactly;
import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertOutputMatchesFileContents;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class UnconfiguredQueryCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  /**
   * =============================================================================================
   * ====================================== Output Formats =======================================
   * =============================================================================================
   */
  @Test
  public void basicTargetPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "//lib:foo");
    assertOutputMatches("//lib:foo", result);
  }

  @Test
  public void basicJsonPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "//lib:", "--output-format", "json");
    assertJSONOutputMatchesFileContents("stdout-basic-json-printing.json", result, workspace);
  }

  @Test
  public void basicMultiQueryPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "%s", "//bin:", "//config/platform:");
    assertOutputMatchesFileContents("stdout-basic-multi-query-printing", result, workspace);
  }

  @Test
  public void basicMultiQueryJsonPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "uquery", "%s", "//lib:", "//config/platform:", "--output-format", "json");
    assertJSONOutputMatchesFileContents(
        "stdout-basic-multi-query-json-printing.json", result, workspace);
  }

  /**
   * =============================================================================================
   * =============================== General uquery functionality ================================
   * =============================================================================================
   */
  @Test
  @Ignore // TODO(srice): owner function NYI
  public void doesntConfigureDependenciesOfTargetForPlatform() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    // Since `foo-bin` is set up for java8 running this on a configured graph would give no results,
    // since DevtoolsEleven is only used on java11 platforms.
    ProcessResult result =
        workspace.runBuckCommand("uquery", "deps(//bin:foo-bin) ^ owner(lib/DevtoolsEleven)");
    assertOutputMatches("//lib:devtools", result);
  }

  @Test
  @Ignore // TODO(srice): inputs function NYI
  public void targetPlatformsArgDoesntChangeOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    String query = "inputs(//lib:devtools)";
    String expected = "lib/DevtoolsEight.java\nlib/DevtoolsEleven.java";

    ProcessResult resultForNoArg = workspace.runBuckCommand("uquery", query);
    assertOutputMatches(expected, resultForNoArg);

    ProcessResult resultForSpecificPlatform =
        workspace.runBuckCommand(
            "uquery", query, "--target-platforms", "//config/platform:java11-dev");
    assertOutputMatches(expected, resultForSpecificPlatform);
  }

  /**
   * =============================================================================================
   * ================================== Function specific tests ==================================
   * =============================================================================================
   */
  @Test
  public void depsFunctionPrintsDependenciesOfTargetInAnyConfiguration() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "deps(//bin:foo-bin)");
    assertOutputMatchesFileContents(
        "stdout-deps-function-prints-dependencies-of-target-in-any-configuration",
        result,
        workspace);
  }

  @Test
  public void kindFunctionOnlyPrintsTargetsOfSpecificType() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "kind(keystore, //bin:)");
    assertOutputMatches("//bin:keystore-debug\n//bin:keystore-prod", result);
  }

  @Test
  public void labelsFunctionPrintsTargetsFromSpecificAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "labels(keystore, //bin:foo-bin)");
    assertOutputMatchesFileContents(
        "stdout-labels-function-prints-targets-from-specific-attribute", result, workspace);
  }

  @Test
  public void labelsFunctionCanPrintFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("uquery", "labels(properties, //bin:keystore-prod)");
    assertOutputMatchesExactly("bin/prod.keystore.properties\n", result);
  }

  @Test
  @Ignore // TODO(srice): owner function NYI
  public void ownerFunctionPrintsTargetsWithGivenFileInSrcs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "owner(lib/DevtoolsEight.java)");
    assertOutputMatches("//lib:devtools", result);
  }

  @Test
  public void rdepsFunctionPrintsNodesWithIncomingEdgesToTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    // We're being a bit tricky here. On a configured graph this intersection would return nothing,
    // since `keystore-prod` is only used in configurations where `devtools` isn't used. We're
    // not operating on the configured graph though.
    ProcessResult result =
        workspace.runBuckCommand(
            "uquery", "rdeps(//bin:, //bin:keystore-prod) ^ rdeps(//bin:, //lib:devtools)");
    assertOutputMatches("//bin:bar-bin\n//bin:foo-bin", result);
  }
}
