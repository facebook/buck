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

package com.facebook.buck.parser;

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.api.Syntax;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class ParserIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testParserFilesAreSandboxed() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "parser_with_method_overrides", temporaryFolder);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:base_genrule");
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    ProcessResult buildResult = workspace.runBuckCommand("build", "", "-v", "2");
    buildResult.assertSuccess();

    workspace.verify(
        RelPath.get("base_genrule_output.expected"),
        BuildTargetPaths.getGenPath(filesystem.getBuckPaths(), target, "%s"));
  }

  /**
   * If a rule contains an erroneous dep to a non-existent rule, then it should throw an appropriate
   * message to help the user find the source of his error.
   */
  @Test
  public void testParseRuleWithBadDependency() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "parse_rule_with_bad_dependency", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckCommand("build", "//:base");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "This error happened while trying to get dependency '//:bad-dep' of target '//:base'"));
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

    ProcessResult processResult = workspace.runBuckCommand("build", "//:A");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        anyOf(
            containsString(
                linesToText(
                    "Buck can't handle circular dependencies.",
                    "The following circular dependency has been found:",
                    "//:C -> //:E -> //:F -> //:C",
                    "",
                    "Please break the circular dependency and try again.")),
            containsString(
                linesToText(
                    "Buck can't handle circular dependencies.",
                    "The following circular dependency has been found:",
                    "//:E -> //:F -> //:C -> //:E",
                    "",
                    "Please break the circular dependency and try again.")),
            containsString(
                linesToText(
                    "Buck can't handle circular dependencies.",
                    "The following circular dependency has been found:",
                    "//:F -> //:C -> //:E -> //:F",
                    "",
                    "Please break the circular dependency and try again."))));
  }

  /**
   * If a .buckconfig is overridden to set allow_empty_glob to False, a glob call returning no
   * results will cause the build to fail.
   */
  @Ignore // Starlark ignores this buckconfig
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

  @Ignore // Starlark parse does not respect ignores
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
    assertEquals("//:root" + System.lineSeparator(), result.getStdout());
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
    result.assertExitCode("missing name should error", ExitCode.BUILD_ERROR);
    assertThat(
        result.getStderr(),
        containsString("One and only one of 'out' or 'outs' must be present in genrule"));
  }

  @Test
  public void testExtraUnknownAttribute() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "extra_attr", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "//:gr");
    result.assertExitCode("extra attr should error", ExitCode.PARSE_ERROR);
    assertThat(result.getStderr(), containsString("genrule"));
    assertThat(result.getStderr(), containsString("blurgle"));
  }

  @Test
  public void testBoundaryChecksAreEnforced() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "package_boundaries", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckCommand("build", "//java:foo");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("can only be referenced from"));

    workspace.addBuckConfigLocalOption("project", "check_package_boundary", "false");
    workspace.runBuckCommand("build", "//java:foo").assertSuccess();

    workspace.addBuckConfigLocalOption("project", "check_package_boundary", "true");
    workspace.addBuckConfigLocalOption("project", "package_boundary_exceptions", "java");
    workspace.runBuckCommand("build", "//java:foo").assertSuccess();

    processResult = workspace.runBuckCommand("build", "//java2:foo");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("can only be referenced from"));
  }

  @Test
  public void packageVisibilityIsEnforced() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "package_visibility", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:should_pass").assertSuccess();
    workspace.runBuckCommand("build", "//:should_pass_2").assertSuccess();

    ProcessResult processResult = workspace.runBuckCommand("build", "//:should_fail");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("which is not visible"));

    workspace.runBuckCommand("build", "//bar:should_pass").assertSuccess();

    processResult = workspace.runBuckCommand("build", "//bar:should_fail");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("which is not visible"));
  }

  @Test
  public void parentPackageVisibilityIsEnforced() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "package_inheritance", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:should_pass").assertSuccess();
    workspace.runBuckCommand("build", "//:should_pass_2").assertSuccess();

    ProcessResult processResult = workspace.runBuckCommand("build", "//:should_fail");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("which is not visible"));

    // Verify foo targets
    workspace.runBuckCommand("build", "//foo:baz").assertSuccess();
    workspace.runBuckCommand("build", "//foo:qux").assertSuccess();
    workspace.runBuckCommand("build", "//foo:waldo").assertSuccess();

    // Verify bar targets
    workspace.runBuckCommand("build", "//bar:should_pass").assertSuccess();
    workspace.runBuckCommand("build", "//bar:should_pass_2").assertSuccess();

    processResult = workspace.runBuckCommand("build", "//bar:should_fail");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("which is not visible"));

    // Verify targets that depend on //baz/...
    workspace.runBuckCommand("build", "//bar:should_pass_3").assertSuccess();
    processResult = workspace.runBuckCommand("build", "//foo:should_fail");
    processResult.assertFailure();
    assertThat(processResult.getStderr(), containsString("which is not visible"));
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
    workspace.runBuckCommand("build", "//foo:foo").assertSuccess();

    workspace.copyFile("foo/BUCK.2", "foo/BUCK");
    workspace.copyFile("foo/bar/BUCK.1", "foo/bar/BUCK");
    byte[] modifiedContents = "yyy".getBytes();
    bigFileTree.visit(path -> Files.write(path, modifiedContents));
    workspace.runBuckCommand("build", "//foo/bar:bar").assertSuccess();
  }

  @Test
  public void testSkylarkParsingOfJavaTargets() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "skylark", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild("//java/bar:bar", "-c", "parser.polyglot_parsing_enabled_deprecated=true")
        .assertSuccess();
    workspace
        .runBuckBuild("//java/bar:main", "-c", "parser.polyglot_parsing_enabled_deprecated=true")
        .assertSuccess();
    workspace
        .runBuckBuild(
            "//java/bar:bar_test", "-c", "parser.polyglot_parsing_enabled_deprecated=true")
        .assertSuccess();
  }

  @Test
  public void absoluteTargetPathInCellResolvesRelativeToCellRootInSkylark() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cross_cell_load", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild("b//:lib2.bzl", "-c", "parser.polyglot_parsing_enabled_deprecated=true")
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
  public void testDisablingImplicitNativeRulesStarlark() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "disable_implicit_native_rules", temporaryFolder);
    workspace.setUp();

    // TODO: Specific error messages are disabled until we hook up the skylark parser to the
    // general buck event bus, since that's how we get messages in integration tests (and how
    // the python parser is hooked up)

    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//skylark/implicit_in_build_file:main",
            "-c",
            "parser.polyglot_parsing_enabled_deprecated=true",
            "-c",
            "parser.disable_implicit_native_rules=true"),
        "BUCK:2:1: name 'java_library' is not defined");
    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//skylark/implicit_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled_deprecated=true",
            "-c",
            "parser.disable_implicit_native_rules=true"),
        "name 'java_library' is not defined",
        "extension.bzl:5",
        "BUCK:2");
    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//skylark/native_in_build_file:main",
            "-c",
            "parser.polyglot_parsing_enabled_deprecated=true",
            "-c",
            "parser.disable_implicit_native_rules=true"),
        "BUCK\", line 2, column 7",
        "'native' value has no field or method 'java_library'");
    workspace
        .runBuckBuild(
            "//skylark/native_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled_deprecated=true",
            "-c",
            "parser.disable_implicit_native_rules=true")
        .assertSuccess();

    workspace
        .runBuckBuild(
            "//skylark/implicit_in_build_file:main",
            "-c",
            "parser.polyglot_parsing_enabled_deprecated=true",
            "-c",
            "parser.disable_implicit_native_rules=false")
        .assertSuccess();
    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//skylark/implicit_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled_deprecated=true",
            "-c",
            "parser.disable_implicit_native_rules=false"),
        "name 'java_library' is not defined",
        "extension.bzl:5",
        "BUCK:2");
    workspace
        .runBuckBuild(
            "//skylark/native_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled_deprecated=true",
            "-c",
            "parser.disable_implicit_native_rules=false")
        .assertSuccess();

    workspace
        .runBuckBuild(
            "//skylark/implicit_in_build_file:main",
            "-c",
            "parser.polyglot_parsing_enabled_deprecated=true",
            "-c",
            "parser.default_build_file_syntax_deprecated=SKYLARK")
        .assertSuccess();
    assertParseFailedWithSubstrings(
        workspace.runBuckBuild(
            "//skylark/implicit_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled_deprecated=true",
            "-c",
            "parser.default_build_file_syntax_deprecated=SKYLARK"),
        "name 'java_library' is not defined",
        "extension.bzl:5",
        "BUCK:2");
    workspace
        .runBuckBuild(
            "//skylark/native_in_extension_bzl:main",
            "-c",
            "parser.polyglot_parsing_enabled_deprecated=true",
            "-c",
            "parser.default_build_file_syntax_deprecated=SKYLARK")
        .assertSuccess();
  }

  @Test
  public void defaultSyntaxByDefault() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "parser_default_syntax_by_prefix", temporaryFolder);
    workspace.setUp();

    ImmutableList<Pair<String, String>> expected =
        ImmutableList.of(
            new Pair<>("//:g", "python"),
            new Pair<>("//s:g", "starlark"),
            new Pair<>("//p:g", "python"),
            new Pair<>("//s/p:g", "python"));

    for (Pair<String, String> targetExpectedPair : expected) {
      String target = targetExpectedPair.getFirst();
      Path output = workspace.buildAndReturnOutput(target);
      List<String> outputLines = Files.readAllLines(output);
      assertEquals(
          "for target " + target, ImmutableList.of(targetExpectedPair.getSecond()), outputLines);
    }
  }

  @Test
  public void parseErrorIfTopLevelRecursiveGlob() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "top_level_recursive_glob", temporaryFolder);
    workspace.setUp();
    assertParseFailedWithSubstrings(
        workspace.runBuckCommand("build", "//:glob"),
        "Recursive globs are prohibited at top-level directory");
  }

  @Test
  public void skylarkParseErrorIfTopLevelRecursiveGlob() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "top_level_recursive_glob", temporaryFolder);
    workspace.setUp();
    assertParseFailedWithSubstrings(
        workspace.runBuckCommand(
            "build", "//:glob", "-c", "parser.default_build_file_syntax_deprecated=skylark"),
        "Recursive globs are prohibited at top-level directory");
  }

  @Test
  public void parseAllFromRootCellShouldIgnoreSubcells() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "subcell_ignored", temporaryFolder);
    workspace.setUp();
    workspace.runBuckBuild("//...").assertSuccess();
  }

  private Syntax[] syntaxes() {
    return Syntax.values();
  }

  @Test
  @Parameters(method = "syntaxes")
  public void sha256(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "sha256", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild("//...", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
        .assertSuccess();
  }

  @Test
  @Parameters(method = "syntaxes")
  public void notJustList(Syntax syntax) throws Exception {
    // Check that parsers accept any sequence, not just list when parameter is list.
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "not_just_list", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild("//...", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
        .assertSuccess();
  }

  @Test
  @Parameters(method = "syntaxes")
  public void loadSymbols(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "load_symbols", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild("//...", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
        .assertSuccess();
  }

  @Test
  @Parameters(method = "syntaxes")
  public void partial(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "partial", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild("//...", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
        .assertSuccess();
  }

  @Test
  @Parameters(method = "syntaxes")
  public void select_introspection(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "select_introspection", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild("//...", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
        .assertSuccess();
  }

  @Ignore // There's no `get_base_path` function in Starlark
  @Test
  public void getBasePath() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "get_base_path_is_deprecated", temporaryFolder);
    workspace.setUp();
    ProcessResult result = workspace.runBuckBuild("//ddd:g").assertSuccess();
    Path outTxt = workspace.getGenPath(BuildTargetFactory.newInstance("//ddd:g"), "%s/out.txt");
    assertEquals(ImmutableList.of("ddd"), Files.readAllLines(outTxt));
    assertThat(result.getStderr(), containsString("get_base_path() function is deprecated"));
  }

  @Test
  @Parameters(method = "syntaxes")
  public void readConfigTopLevel(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "read_config", temporaryFolder);
    workspace.setUp();
    ProcessResult result =
        workspace
            .runBuckBuild(
                "//:",
                "-c",
                "parser.default_build_file_syntax_deprecated=" + syntax,
                "-c",
                "foo.bar=br",
                "-c",
                "foo.baz=bz")
            .assertSuccess();
    assertThat(result.getStderr(), containsString("bar = br, baz = bz"));
  }

  @Test
  @Parameters(method = "syntaxes")
  public void recursion(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "recursion", temporaryFolder);
    workspace.setUp();
    ProcessResult result =
        workspace
            .runBuckBuild("//:", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
            .assertSuccess();
    assertThat(result.getStderr(), containsString("factorial of 5 is 120"));
  }

  @Test
  @Parameters(method = "syntaxes")
  public void loadTwiceFromBuck(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "load_twice_from_buck", temporaryFolder);
    workspace.setUp();
    ProcessResult result =
        workspace
            .runBuckBuild("//:", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
            .assertSuccess();
    assertThat(result.getStderr(), containsString("x=1 y=2 z=3 w=4"));
  }

  @Test
  @Parameters(method = "syntaxes")
  public void loadTwiceFromBzl(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "load_twice_from_bzl", temporaryFolder);
    workspace.setUp();
    ProcessResult result =
        workspace
            .runBuckBuild("//:", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
            .assertSuccess();
    assertThat(result.getStderr(), containsString("x=1 y=2 z=3 w=4"));
  }

  @Test
  @Parameters(method = "syntaxes")
  public void reexport(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "reexport", temporaryFolder);
    workspace.setUp();
    ProcessResult result =
        workspace
            .runBuckBuild("//:", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
            .assertSuccess();
    assertThat(result.getStderr(), containsString("x = 17"));
  }

  @Test
  public void recursiveLoad() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "recursive_load", temporaryFolder);
    workspace.setUp();
    ProcessResult processResult = workspace.runBuckBuild("//...");
    assertEquals(ExitCode.PARSE_ERROR, processResult.getExitCode());
    assertThat(processResult.getStderr(), containsString("Load cycle while loading"));
  }

  @Test
  @Parameters(method = "syntaxes")
  public void selectEqual(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "select_equal", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild("//...", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
        .assertSuccess();
  }

  @Test
  @Parameters(method = "syntaxes")
  public void selectAdd(Syntax syntax) throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "select_add", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckBuild("//...", "-c", "parser.default_build_file_syntax_deprecated=" + syntax)
        .assertSuccess();
  }

  @Test
  public void testCellBoundaryChecksAreEnforced() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cell_boundaries", temporaryFolder);
    workspace.setUp();
    workspace
        .runBuckCommand("targets", "-c", "project.check_cell_boundary=true", "other//:bar")
        .assertSuccess();
    workspace
        .runBuckCommand("targets", "-c", "project.check_cell_boundary=false", "//other:bar")
        .assertSuccess();
    workspace
        .runBuckCommand("targets", "-c", "project.check_cell_boundary=true", "//other:bar")
        .assertFailure("but is owned by cell");
    workspace
        .runBuckCommand("targets", "-c", "project.check_cell_boundary=false", "//:foo")
        .assertSuccess();
    workspace
        .runBuckCommand("targets", "-c", "project.check_cell_boundary=true", "//:foo")
        .assertFailure("but is owned by cell");
  }
}
