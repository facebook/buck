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

import static com.facebook.buck.testutil.integration.ProcessOutputAssertions.assertOutputMatches;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

// TODO(srice): These tests are more aspirational than functional right now. They describe behavior
// that uquery _should_ have, not behavior it has. Once we have a real implementation of uquery
// (one that isn't just `throw new NotImplementedException()`) we should remove this Ignore.
@Ignore
public class UnconfiguredQueryCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void basicTargetPrinting() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "//lib:foo");
    assertOutputMatches("//lib:foo", result);
  }

  @Test
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

  @Test
  public void kindFunctionOnlyPrintsTargetsOfSpecificType() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "kind(keystore, //bin:)");
    assertOutputMatches("//bin:keystore-debug\n//bin:keystore-prod", result);
  }

  @Test
  public void ownerFunctionPrintsTargetsWithGivenFileInSrcs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sample_android", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("uquery", "owner(lib/DevtoolsEight.java)");
    assertOutputMatches("//lib:devtools", result);
  }
}
