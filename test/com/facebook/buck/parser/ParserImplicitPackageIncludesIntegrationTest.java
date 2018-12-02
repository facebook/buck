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

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.stream.StreamSupport;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ParserImplicitPackageIncludesIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> getParsers() {
    return ImmutableList.of(new String[] {"python_dsl"}, new String[] {"skylark"});
  }

  @Parameterized.Parameter(value = 0)
  public String parser;

  @Test
  public void implicitPackageSymbolsAreVisible() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "package_implicit_includes", temporaryFolder);
    workspace.setUp();
    // The build files use implicit functions in generating their names, so if implicits break,
    // or the wrong functions are called, the targets just won't exist, and the query will fail.
    ProcessResult result =
        workspace.runBuckBuild(
            "//root:root__root",
            "//root/sibling:root__root",
            "//root/foo:root__root",
            "//root/foo/bar:subdir__subdir",
            "//root/foo/bar/baz:subdir__subdir",
            "-c",
            "buildfile.package_includes=root=>//root:name.bzl::NAME,root/foo/bar=>//root/foo/bar:name.bzl::NAME=SOME_OTHER_NAME",
            "-c",
            "parser.default_build_file_syntax=" + parser);

    result.assertSuccess();
  }

  @Test
  public void crossCellImplicitPackageSymbolsAreVisible() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "package_implicit_includes", temporaryFolder);
    workspace.setUp();
    // The build files use implicit functions in generating their names, so if implicits break,
    // or the wrong functions are called, the targets just won't exist, and the query will fail.
    ProcessResult result =
        workspace.runBuckBuild(
            "//root:cell__cell",
            "-c",
            "buildfile.package_includes=root=>@cell//:name.bzl::NAME",
            "-c",
            "parser.default_build_file_syntax=" + parser);

    result.assertSuccess();
  }

  @Test
  public void defaultValuesAreUsedIfNoImplicitPackageSymbolIsAvailable() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "package_implicit_includes", temporaryFolder);
    workspace.setUp();

    // The build files use implicit functions in generating their names, so if implicits break,
    // or the wrong functions are called, the targets just won't exist, and the query will fail.
    ProcessResult result =
        workspace.runBuckBuild(
            "//default:root__root", // No file
            "//default/no_symbol:root__root", // Invalid/missing symbol
            "//default/has_symbol:some_name__some_name", // symbol is present
            "-c",
            "buildfile.package_includes=default/no_symbol=>//default/no_symbol:name.bzl::NAME,default/has_symbol=>//default/has_symbol:name.bzl::NAME",
            "-c",
            "parser.default_build_file_syntax=" + parser);

    result.assertSuccess();
  }

  @Test
  public void failsOnInvalidSymbolsRequested() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "package_implicit_includes", temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckBuild(
            "//default:root__root",
            "-c",
            "buildfile.package_includes=default=>//:get_suffix.bzl::MISSING",
            "-c",
            "parser.default_build_file_syntax=" + parser);

    result.assertExitCode(ExitCode.PARSE_ERROR);
  }

  @Test
  public void failsOnInvalidFileRequested() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "package_implicit_includes", temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckBuild(
            "//default:root__root",
            "-c",
            "buildfile.package_includes=default=>//:missing.bzl::NAME",
            "-c",
            "parser.default_build_file_syntax=" + parser);

    result.assertExitCode(ExitCode.PARSE_ERROR);
  }

  @Test
  public void includesAreListed() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "package_implicit_includes", temporaryFolder);
    workspace.setUp();
    String implicits =
        "buildfile.package_includes=default/no_symbol=>//default/no_symbol:name.bzl::NAME,default/has_symbol=>//default/has_symbol:name.bzl::NAME";

    ImmutableList<Path> expectedRoot =
        ImmutableList.of(
            workspace.getDestPath().resolve("default").resolve("BUCK"),
            workspace.getDestPath().resolve("get_suffix.bzl"));

    ImmutableList<Path> expectedNoSymbol =
        ImmutableList.of(
            workspace.getDestPath().resolve("default").resolve("no_symbol").resolve("BUCK"),
            workspace.getDestPath().resolve("default").resolve("no_symbol").resolve("name.bzl"),
            workspace.getDestPath().resolve("get_suffix.bzl"));

    ImmutableList<Path> expectedHasSymbol =
        ImmutableList.of(
            workspace.getDestPath().resolve("default").resolve("has_symbol").resolve("BUCK"),
            workspace.getDestPath().resolve("default").resolve("has_symbol").resolve("name.bzl"),
            workspace.getDestPath().resolve("get_suffix.bzl"));

    ObjectMapper mapper = new ObjectMapper();

    ProcessResult rootResult =
        workspace.runBuckCommand(
            "audit",
            "includes",
            "--json",
            "-c",
            implicits,
            "-c",
            "parser.default_build_file_syntax=" + parser,
            "default/BUCK",
            "default/has_symbol/BUCK",
            "default/no_symbol/BUCK");

    ProcessResult noSymbolResult =
        workspace.runBuckCommand(
            "audit",
            "includes",
            "--json",
            "-c",
            implicits,
            "-c",
            "parser.default_build_file_syntax=" + parser,
            "default/no_symbol/BUCK");

    ProcessResult hasSymbolResult =
        workspace.runBuckCommand(
            "audit",
            "includes",
            "--json",
            "-c",
            implicits,
            "-c",
            "parser.default_build_file_syntax=" + parser,
            "default/has_symbol/BUCK");

    rootResult.assertSuccess();
    noSymbolResult.assertSuccess();
    hasSymbolResult.assertSuccess();

    ImmutableList<Path> rootActual =
        StreamSupport.stream(mapper.readTree(rootResult.getStdout()).spliterator(), false)
            .map(js -> Paths.get(js.asText()))
            .collect(ImmutableList.toImmutableList());

    ImmutableList<Path> noSymbolActual =
        StreamSupport.stream(mapper.readTree(noSymbolResult.getStdout()).spliterator(), false)
            .map(js -> Paths.get(js.asText()))
            .collect(ImmutableList.toImmutableList());

    ImmutableList<Path> hasSymbolActual =
        StreamSupport.stream(mapper.readTree(hasSymbolResult.getStdout()).spliterator(), false)
            .map(js -> Paths.get(js.asText()))
            .collect(ImmutableList.toImmutableList());

    Assert.assertThat(rootActual, Matchers.containsInAnyOrder(expectedRoot.toArray()));
    Assert.assertThat(noSymbolActual, Matchers.containsInAnyOrder(expectedNoSymbol.toArray()));
    Assert.assertThat(hasSymbolActual, Matchers.containsInAnyOrder(expectedHasSymbol.toArray()));
  }
}
