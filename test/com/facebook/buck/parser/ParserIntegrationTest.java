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
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ParserIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths(true);
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testParserFilesAreSandboxed() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "parser_with_method_overrides", temporaryFolder);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:base_genrule");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    ProcessResult buildResult = workspace.runBuckCommand("build", "", "-v", "2");
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
    thrown.expectMessage(
        "This error happened while trying to get dependency '//:bad-dep' of target '//:base'");

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
                      "Buck can't handle circular dependencies.\n"
                          + "The following circular dependency has been found:\n"
                          + "//:C -> //:E -> //:F -> //:C\n\n"
                          + "Please break the circular dependency and try again.",
                      "Buck can't handle circular dependencies.\n"
                          + "The following circular dependency has been found:\n"
                          + "//:E -> //:F -> //:C -> //:E\n\n"
                          + "Please break the circular dependency and try again.",
                      "Buck can't handle circular dependencies.\n"
                          + "The following circular dependency has been found:\n"
                          + "//:F -> //:C -> //:E -> //:F\n\n"
                          + "Please break the circular dependency and try again."))));
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

    ProcessResult result = workspace.runBuckCommand("build", "//:root_module");
    result.assertExitCode(
        "buck build should fail on empty glob results when set in config", ExitCode.PARSE_ERROR);
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

    ProcessResult result = workspace.runBuckCommand("build", "//:root_module");
    result.assertSuccess("buck build should ignore empty glob results by default");
  }

  @Test
  public void ignoredFilesAreNotReturnedByGlob() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "glob_ignores", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//:root_module");
    result.assertExitCode("glob should be empty because of ignores", ExitCode.PARSE_ERROR);
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

    ProcessResult result = workspace.runBuckCommand("targets", "//:root");
    result.assertSuccess("buck should parse build files with a different name");
    assertEquals("//:root\n", result.getStdout());
  }

  @Test
  public void testMissingName() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_name", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "//...");
    result.assertExitCode("missing attribute should error", ExitCode.PARSE_ERROR);
    assertThat(result.getStderr(), containsString("genrule"));
    assertThat(result.getStderr(), containsString("name"));
  }

  @Test
  public void testMissingRequiredAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_attr", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "//:gr");
    result.assertExitCode("missing name should error", ExitCode.PARSE_ERROR);
    assertThat(result.getStderr(), containsString("genrule"));
    assertThat(result.getStderr(), containsString("gr"));
    assertThat(result.getStderr(), containsString("out"));
  }

  @Test
  public void testExtraUnknownAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "extra_attr", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "//:gr");
    result.assertExitCode("extra attr should error", ExitCode.PARSE_ERROR);
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
      assertThat(e.getMessage(), containsString("can only be referenced from"));
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
      assertThat(e.getMessage(), containsString("can only be referenced from"));
    }
  }

  static class BigFileTree {
    private final ProjectWorkspace workspace;

    BigFileTree(ProjectWorkspace workspace) {
      this.workspace = workspace;
    }

    interface LeafVisitor {
      void visit(Path path) throws IOException;
    }

    public void visit(LeafVisitor visitor) throws IOException {
      for (int i = 0; i < 10; i++) {
        Path levelOne = workspace.resolve(Integer.toString(i));
        Files.createDirectories(levelOne);
        for (int j = 0; j < 10; j++) {
          Path levelTwo = levelOne.resolve(Integer.toString(j));
          Files.createDirectories(levelTwo);
          for (int k = 0; k < 100; k++) {
            Path leafFile = levelTwo.resolve(Integer.toString(k));
            visitor.visit(leafFile);
          }
        }
      }
    }
  }

  @Ignore
  @Test
  public void testOverflowInvalidatesBuildFileTree() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "overflow", temporaryFolder);
    workspace.setUp();
    BigFileTree bigFileTree = new BigFileTree(workspace);

    // We need to change a bunch of files to trigger watchman overflow.  Build a directory hierarchy
    // to avoid overstuffing any individual directory.
    byte[] initialContents = "xxx".getBytes();
    bigFileTree.visit(path -> Files.write(path, initialContents));

    workspace.copyFile("foo/BUCK.1", "foo/BUCK");
    workspace.runBuckdCommand("build", "//foo:foo").assertSuccess();

    workspace.copyFile("foo/BUCK.2", "foo/BUCK");
    workspace.copyFile("foo/bar/BUCK.1", "foo/bar/BUCK");
    byte[] modifiedContents = "yyy".getBytes();
    bigFileTree.visit(path -> Files.write(path, modifiedContents));
    workspace.runBuckdCommand("build", "//foo/bar:bar").assertSuccess();
  }

  @Test
  public void testSkylarkParsingOfJavaTargets() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "skylark", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild("//java/bar:bar", "-c", "parser.polyglot_parsing_enabled=true")
        .assertSuccess();
    workspace
        .runBuckBuild("//java/bar:main", "-c", "parser.polyglot_parsing_enabled=true")
        .assertSuccess();
    workspace
        .runBuckBuild("//java/bar:bar_test", "-c", "parser.polyglot_parsing_enabled=true")
        .assertSuccess();
  }

  @Test
  public void testPythonDSLParsingHasNoWarningsForLoadsWithoutCell() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "python_dsl_warnings", temporaryFolder);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckBuild(
            "cell//:ext.bzl",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.default_build_file_syntax=python_dsl");
    System.out.println(result.getStderr());
    assertThat(result.getStderr(), not(containsString("Warning raised by BUCK file parser")));
    result.assertSuccess();
  }

  @Test
  public void absoluteTargetPathInCellResolvesRelativeToCellRootInSkylark() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cross_cell_load", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild(
            "b//:lib2.bzl",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.default_build_file_syntax=skylark")
        .assertSuccess();
  }

  @Test
  public void absoluteTargetPathInCellResolvesRelativeToCellRootInPythonDSL() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cross_cell_load", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild(
            "b//:lib2.bzl",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.default_build_file_syntax=python_dsl")
        .assertSuccess();
  }

  private void assertParseFailedWithSubstrings(ProcessResult result, String... substrings) {
    result.assertExitCode("", ExitCode.PARSE_ERROR);
    System.out.println(result.getStderr());
    for (String substring : substrings) {
      assertThat(result.getStderr(), containsString(substring));
    }
  }

  @Test
  public void testDisablingImplicitNativeRules() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "disable_implicit_native_rules", temporaryFolder);
    workspace.setUp();

    // Python interpreter, true / false / default for disabling implicit native rules

    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//python/implicit_in_build_file:main",
            "-c",
            "parser.disable_implicit_native_rules=true"),
        "NameError: name 'java_library' is not defined",
        "BUCK\", line 1");
    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//python/implicit_in_extension_bzl:main",
            "-c",
            "parser.disable_implicit_native_rules=true"),
        "NameError: global name 'java_library' is not defined",
        "extension.bzl\", line 5",
        "BUCK\", line 5");
    workspace
        .runBuckBuild(
            "//python/native_in_extension_bzl:main",
            "-c",
            "parser.disable_implicit_native_rules=true")
        .assertSuccess();

    workspace
        .runBuckBuild(
            "//python/implicit_in_build_file:main",
            "-c",
            "parser.disable_implicit_native_rules=false")
        .assertSuccess();
    workspace
        .runBuckBuild(
            "//python/implicit_in_extension_bzl:main",
            "-c",
            "parser.disable_implicit_native_rules=false")
        .assertSuccess();
    workspace
        .runBuckBuild(
            "//python/native_in_extension_bzl:main",
            "-c",
            "parser.disable_implicit_native_rules=false")
        .assertSuccess();

    workspace.runBuckBuild("//python/implicit_in_build_file:main").assertSuccess();
    workspace.runBuckBuild("//python/implicit_in_extension_bzl:main").assertSuccess();
    workspace.runBuckBuild("//python/native_in_extension_bzl:main").assertSuccess();

    // Skylark interpreter, true / false / default for disabling implicit native rules
    // TODO: Specific error messages are disabled until we hook up the skylark parser to the
    // general buck event bus, since that's how we get messages in integration tests (and how
    // the python parser is hooked up)

    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//skylark/implicit_in_build_file:main",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.disable_implicit_native_rules=true"),
        "BUCK:2:1: name 'java_library' is not defined");
    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//skylark/implicit_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.disable_implicit_native_rules=true"),
        "name 'java_library' is not defined",
        "extension.bzl\", line 5",
        "BUCK\", line 4");
    workspace
        .runBuckBuild(
            "//skylark/native_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.disable_implicit_native_rules=true")
        .assertSuccess();

    workspace
        .runBuckBuild(
            "//skylark/implicit_in_build_file:main",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.disable_implicit_native_rules=false")
        .assertSuccess();
    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//skylark/implicit_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.disable_implicit_native_rules=false"),
        "name 'java_library' is not defined",
        "extension.bzl\", line 5",
        "BUCK\", line 4");
    workspace
        .runBuckBuild(
            "//skylark/native_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.disable_implicit_native_rules=false")
        .assertSuccess();

    workspace
        .runBuckBuild(
            "//skylark/implicit_in_build_file:main",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.default_build_file_syntax=SKYLARK")
        .assertSuccess();
    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//skylark/implicit_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.default_build_file_syntax=SKYLARK"),
        "name 'java_library' is not defined",
        "extension.bzl\", line 5",
        "BUCK\", line 4");
    workspace
        .runBuckBuild(
            "//skylark/native_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled=true",
            "-c",
            "parser.default_build_file_syntax=SKYLARK")
        .assertSuccess();
  }

  @Test
  public void deprecatedSyntaxWarningIsDisplayedWhenReferencingACellWithoutAt() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "deprecated_cell_syntax", temporaryFolder);
    workspace.setUp();
    ProcessResult result =
        workspace
            .runBuckBuild(
                "cell//:lib.bzl",
                "-c",
                "parser.polyglot_parsing_enabled=true",
                "-c",
                "parser.default_build_file_syntax=python_dsl",
                "-c",
                "parser.warn_about_deprecated_syntax=true")
            .assertSuccess();
    assertThat(
        result.getStderr(),
        containsString(
            "BUCK has a load label \"cell//:lib.bzl\" that uses a deprecated cell format."
                + " \"cell\" should instead be \"@cell\"."));
    assertThat(
        result.getStderr(),
        containsString(
            "lib.bzl has a load label \"cell//:lib2.bzl\" that uses a deprecated cell format. "
                + "\"cell\" should instead be \"@cell\"."));
  }

  @Test
  public void deprecatedSyntaxWarningIsNotDisplayedIfDisabled() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "deprecated_cell_syntax", temporaryFolder);
    workspace.setUp();
    ProcessResult result =
        workspace
            .runBuckBuild(
                "cell//:lib.bzl",
                "-c",
                "parser.polyglot_parsing_enabled=true",
                "-c",
                "parser.default_build_file_syntax=python_dsl",
                "-c",
                "parser.warn_about_deprecated_syntax=false")
            .assertSuccess();
    assertThat(result.getStderr(), not(containsString("Warning raised by BUCK file parser")));
  }
}
