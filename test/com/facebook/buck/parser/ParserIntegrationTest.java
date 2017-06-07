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

package com.facebook.buck.parser;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ParserIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testParserFilesAreSandboxed() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "parser_with_method_overrides", temporaryFolder);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:base_genrule");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    ProjectWorkspace.ProcessResult buildResult = workspace.runBuckCommand("build", "", "-v", "2");
    buildResult.assertSuccess();

    workspace.verify(
        Paths.get("base_genrule_output.expected"),
        BuildTargets.getGenPath(filesystem, target, "%s"));
  }

  /**
   * If a rule contains an erroneous dep to a non-existent rule, then it should throw an appropriate
   * message to help the user find the source of his error.
   */
  @Test
  public void testParseRuleWithBadDependency() throws IOException {
    // Ensure an exception with a specific message is thrown.
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Couldn't get dependency '//:bad-dep' of target '//:base'");

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "parse_rule_with_bad_dependency", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:base");
  }

  /**
   * Creates the following graph (assume all / and \ indicate downward pointing arrows):
   *
   * <pre>
   *         A
   *       /   \
   *     B       C <----|
   *   /   \   /        |
   * D       E          |
   *   \   /            |
   *     F --------------
   * </pre>
   *
   * Note that there is a circular dependency from C -> E -> F -> C that should be caught by the
   * parser.
   */
  @Test
  public void testCircularDependencyDetection() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "circular_dependency_detection", temporaryFolder);
    workspace.setUp();

    try {
      workspace.runBuckCommand("build", "//:A");
    } catch (HumanReadableException e) {
      assertThat(
          e.getHumanReadableErrorMessage(),
          is(
              in(
                  ImmutableSet.of(
                      "Cycle found: //:C -> //:E -> //:F -> //:C",
                      "Cycle found: //:E -> //:F -> //:C -> //:E",
                      "Cycle found: //:F -> //:C -> //:E -> //:F"))));
      return;
    }
    fail("An exception should have been thrown because of a circular dependency.");
  }

  /**
   * If a .buckconfig is overridden to set allow_empty_glob to False, a glob call returning no
   * results will cause the build to fail.
   */
  @Test
  public void testNotAllowEmptyGlob() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "not_allow_empty_glob", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "//:root_module");
    result.assertFailure("buck build should fail on empty glob results when set in config");
    assertThat(
        "error message for failure to return results from glob is incorrect",
        result.getStderr(),
        containsString(
            "returned no results.  (allow_empty_globs is set to false in the Buck "
                + "configuration)"));
  }

  /** By default a glob call returning no results will not cause the build to fail. */
  @Test
  public void testAllowEmptyGlob() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "allow_empty_glob", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "//:root_module");
    result.assertSuccess("buck build should ignore empty glob results by default");
  }

  @Test
  public void ignoredFilesAreNotReturnedByGlob() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "glob_ignores", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "//:root_module");
    result.assertFailure("glob should be empty because of ignores");
    assertThat(
        "error message for failure to return results from glob is incorrect",
        result.getStderr(),
        containsString(
            "returned no results.  (allow_empty_globs is set to false in the Buck "
                + "configuration)"));
  }

  @Test
  public void testBuildFileName() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_file_name", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("targets", "//:root");
    result.assertSuccess("buck should parse build files with a different name");
    assertEquals("//:root\n", result.getStdout());
  }

  @Test
  public void testMissingName() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_name", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("targets", "//...");
    result.assertFailure("missing attribute should error");
    assertThat(result.getStderr(), containsString("genrule"));
    assertThat(result.getStderr(), containsString("name"));
  }

  @Test
  public void testMissingRequiredAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_attr", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("targets", "//:gr");
    result.assertFailure("missing name should error");
    assertThat(result.getStderr(), containsString("genrule"));
    assertThat(result.getStderr(), containsString("gr"));
    assertThat(result.getStderr(), containsString("out"));
  }

  @Test
  public void testExtraUnknownAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "extra_attr", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("targets", "//:gr");
    result.assertFailure("extra attr should error");
    assertThat(result.getStderr(), containsString("genrule"));
    assertThat(result.getStderr(), containsString("gr"));
    assertThat(result.getStderr(), containsString("blurgle"));
  }

  @Test
  public void testBoundaryChecksAreEnforced() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "package_boundaries", temporaryFolder);
    workspace.setUp();
    try {
      workspace.runBuckCommand("build", "//java:foo");
      fail("Expected exception");
    } catch (HumanReadableException e) {
      assertThat(e.getMessage(), containsString("package boundary"));
    }

    workspace.addBuckConfigLocalOption("project", "check_package_boundary", "false");
    workspace.runBuckCommand("build", "//java:foo").assertSuccess();

    workspace.addBuckConfigLocalOption("project", "check_package_boundary", "true");
    workspace.addBuckConfigLocalOption("project", "package_boundary_exceptions", "java");
    workspace.runBuckCommand("build", "//java:foo").assertSuccess();
    try {
      workspace.runBuckCommand("build", "//java2:foo").assertSuccess();
      fail("Expected exception");
    } catch (HumanReadableException e) {
      assertThat(e.getMessage(), containsString("package boundary"));
    }
  }
}
