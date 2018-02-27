/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class UnusedDependenciesFinderIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws InterruptedException, IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "unused_dependencies", temporaryFolder, true);
    workspace.setUp();
  }

  @Test
  public void testShowWarning() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=warn", ":bar_with_dep");

    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_dep is declared with unused targets in deps:"),
            Matchers.containsString("buck//third-party/java/jsr:jsr305")));
  }

  @Test
  public void testFailBuild() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=fail", ":bar_with_dep");

    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_dep is declared with unused targets in deps:"),
            Matchers.containsString("buck//third-party/java/jsr:jsr305")));
  }

  @Test
  public void testDoNotFailBuildWhenNoUnusedDeps() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=fail", ":bar_without_dep");

    processResult.assertSuccess();
  }

  @Test
  public void testExportedDepsNotReported() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "java.unused_dependencies_action=fail",
            ":bar_with_transitive_exported_dep");

    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_transitive_exported_dep is declared with unused targets in deps:"),
            Matchers.containsString("//:blargh"),
            Matchers.not(Matchers.containsString("buildozer 'remove deps //:meh'"))));
  }

  @Test
  public void testOverridenTargetOptionDoesNotFailBuild() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=fail", ":bar_with_dep_and_skip_option");

    processResult.assertSuccess();
  }

  @Test
  public void testOverridenTargetOptionShowsWarning() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=fail", ":bar_with_dep_and_warn_option");

    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_dep_and_warn_option is declared with unused targets in deps:"),
            Matchers.containsString("buck//third-party/java/jsr:jsr305")));
  }

  @Test
  public void testShowWarningAboutProvidedDeps() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=warn", ":bar_with_provided_dep");

    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_provided_dep is declared with unused targets in provided_deps:"),
            Matchers.containsString("buck//third-party/java/jsr:jsr305")));
  }

  @Test
  public void testShowWarningAboutExportedDeps() throws IOException {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=warn", ":bar_with_exported_dep");

    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_exported_dep is declared with unused targets in exported_deps:"),
            Matchers.containsString("buck//third-party/java/jsr:jsr305")));
  }
}
