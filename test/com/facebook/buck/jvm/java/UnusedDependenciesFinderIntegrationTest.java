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
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "unused_dependencies", temporaryFolder);
    workspace.setUp();
  }

  @Test
  public void testShowWarning() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=warn", ":bar_with_dep");

    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.containsString(
            "Target //:bar_with_dep is declared with unused targets in deps: \n"
                + "buck//third-party/java/jsr:jsr305\n"
                + "\n"
                + "Please remove them. You may be able to use the following commands: \n"
                + "buildozer 'remove deps buck//third-party/java/jsr:jsr305' //:bar_with_dep\n"
                + "\n"
                + "If you are sure that these targets are required, then you may add them as `runtime_deps` instead and they will no longer be detected as unused.\n"));
  }

  @Test
  public void testFailBuild() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=fail", ":bar_with_dep");

    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_dep is declared with unused targets in deps:"),
            Matchers.containsString(
                "buildozer 'remove deps buck//third-party/java/jsr:jsr305' //:bar_with_dep")));
  }

  @Test
  public void testFailBuildWithSpecifiedBuildozerPath() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "java.unused_dependencies_action=fail",
            "-c",
            "java.unused_dependencies_buildozer_path=/some/path/buildozer",
            ":bar_with_dep");

    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_dep is declared with unused targets in deps:"),
            Matchers.containsString(
                "/some/path/buildozer 'remove deps buck//third-party/java/jsr:jsr305' //:bar_with_dep")));
  }

  @Test
  public void testDoNotFailBuildWhenNoUnusedDeps() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=fail", ":bar_without_dep");

    processResult.assertSuccess();
  }

  @Test
  public void testExportedDepsNotReported() {
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
  public void testOverridenTargetOptionDoesNotFailBuild() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=fail", ":bar_with_dep_and_skip_option");

    processResult.assertSuccess();
  }

  @Test
  public void testOverriddenTargetOptionShowsWarning() {
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
  public void testOverriddenTargetOptionFailsBuild() {
    ProcessResult processResult =
        workspace.runBuckCommand("build", ":bar_with_dep_and_fail_option");

    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_dep_and_fail_option is declared with unused targets in deps:"),
            Matchers.containsString("buck//third-party/java/jsr:jsr305")));
  }

  @Test
  public void testAlwaysIgnoreOverridesTargetWarningOption() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "java.unused_dependencies_action=ignore_always",
            ":bar_with_dep_and_warn_option");

    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.not(
                Matchers.containsString(
                    "Target //:bar_with_dep_and_warn_option is declared with unused targets in deps:"))));
  }

  @Test
  public void testAlwaysIgnoreOverridesTargetFailOption() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "java.unused_dependencies_action=ignore_always",
            ":bar_with_dep_and_fail_option");

    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.not(
            Matchers.containsString(
                "Target //:bar_with_dep_and_warn_option is declared with unused targets in deps:")));
  }

  @Test
  public void testWarnIfFailDowngradesTargetFailOption() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "java.unused_dependencies_action=warn_if_fail",
            ":bar_with_dep_and_fail_option");

    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_dep_and_fail_option is declared with unused targets in deps:"),
            Matchers.containsString("buck//third-party/java/jsr:jsr305")));
  }

  @Test
  public void testWarnIfFailWithWarningOnTarget() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "java.unused_dependencies_action=warn_if_fail",
            ":bar_with_dep_and_warn_option");

    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:bar_with_dep_and_warn_option is declared with unused targets in deps:"),
            Matchers.containsString("buck//third-party/java/jsr:jsr305")));
  }

  @Test
  public void testWarnIfFailDoesNoCheckByDefault() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=ignore_always", ":bar_with_dep");

    processResult.assertSuccess();
    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.not(
            Matchers.containsString(
                "Target //:bar_with_dep is declared with unused targets in deps:")));
  }

  @Test
  public void testShowWarningAboutProvidedDeps() {
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
  public void testDoNotFailForExportedDeps() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=fail", ":bar_with_exported_dep");

    processResult.assertSuccess();
  }

  @Test
  public void testDoNotFailForExportedProvidedDeps() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "java.unused_dependencies_action=fail",
            ":bar_with_exported_provided_dep");

    processResult.assertSuccess();
  }

  @Test
  public void testDoNotFailForRuntimeDeps() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=fail", ":bar_with_runtime_dep");

    processResult.assertSuccess();
  }

  @Test
  public void testDoNotFailForNonJavaLibraryDeps() {
    ProcessResult processResult =
        workspace.runBuckCommand("build", "-c", "java.unused_dependencies_action=fail", ":res_dep");

    processResult.assertSuccess();
  }

  @Test
  public void testDoNotFailWithClassUsageTurnedOff() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "java.unused_dependencies_action=fail",
            "-c",
            "java.track_class_usage=false",
            ":bar_with_dep");

    processResult.assertSuccess();
  }

  @Test
  public void testDoNotFailIfMarkedAsNeverUnused() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "java.unused_dependencies_action=fail",
            ":bar_with_dep_marked_as_never_unused");

    processResult.assertSuccess();
  }

  @Test
  public void testFailBuildForDepThatHasExportedDepThatIsAFirstOrderDepAnyway() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build", "-c", "java.unused_dependencies_action=fail", ":barmeh_with_unused_dep");

    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "Target //:barmeh_with_unused_dep is declared with unused targets in deps:"),
            Matchers.containsString(
                "buildozer 'remove deps //:dep_with_exported_dep' //:barmeh_with_unused_dep")));
  }

  @Test
  public void testShowCommandOnly() {
    ProcessResult processResult =
        workspace.runBuckCommand(
            "build",
            "-c",
            "java.unused_dependencies_action=warn",
            "-c",
            "java.unused_dependencies_only_print_commands=true",
            ":bar_with_dep");

    processResult.assertSuccess();
    assertThat(
        processResult.getStderr(),
        Matchers.allOf(
            Matchers.containsString(
                "buildozer 'remove deps buck//third-party/java/jsr:jsr305' //:bar_with_dep"),
            Matchers.not(
                Matchers.containsString(
                    "Target //:bar_with_dep is declared with unused targets in deps: "))));
  }
}
