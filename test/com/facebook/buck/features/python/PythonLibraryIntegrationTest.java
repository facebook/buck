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

package com.facebook.buck.features.python;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.VersionStringComparator;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.hamcrest.comparator.ComparatorMatcherBuilder;
import org.junit.Rule;
import org.junit.Test;

public class PythonLibraryIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  public ProjectWorkspace workspace;

  @Test
  public void excludeDepsFromOmnibus() throws Exception {
    assumeThat(Platform.detect(), Matchers.not(Matchers.is(Platform.WINDOWS)));

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "exclude_deps_from_merged_linking", tmp);
    workspace.setUp();

    workspace.runBuckBuild("-c", "python.native_link_strategy=merged", "//:bin");
  }

  @Test
  public void compile() throws Exception {
    Path py3 = PythonTestUtils.assumeInterpreter("python3");
    PythonTestUtils.assumeVersion(
        py3,
        Matchers.any(String.class),
        ComparatorMatcherBuilder.comparedBy(new VersionStringComparator())
            .greaterThanOrEqualTo("3.7"));
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "python_library_compile", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();
    RelPath dir =
        filesystem.relativize(
            workspace.buildAndReturnOutput(
                "-c", "python.interpreter=" + py3, "//:lib#py-default,default,compile"));
    assertThat(
        filesystem.asView().getFilesUnderPath(dir.getPath(), EnumSet.noneOf(FileVisitOption.class))
            .stream()
            .map(p -> PathFormatter.pathWithUnixSeparators(dir.getPath().relativize(p)))
            .collect(ImmutableList.toImmutableList()),
        Matchers.containsInAnyOrder(
            Matchers.matchesRegex("(__pycache__/)?foo(.cpython-3[0-9])?.pyc")));
  }

  @Test
  public void compileDeterminism() throws Exception {
    Path py3 = PythonTestUtils.assumeInterpreter("python3");
    PythonTestUtils.assumeVersion(
        py3,
        Matchers.any(String.class),
        ComparatorMatcherBuilder.comparedBy(new VersionStringComparator())
            .greaterThanOrEqualTo("3.7"));
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "python_library_compile", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();
    String target = "//:lib#py-default,default,compile";

    // A supplier which uses Buck to compile bytecode and return the content hashes.
    ThrowingSupplier<Map<Path, Sha1HashCode>, IOException> buildAndGetHashes =
        () -> {
          RelPath dir =
              filesystem.relativize(
                  workspace.buildAndReturnOutput(
                      // Generate some randomness to encourage this test to fail if propagated
                      // through.
                      ImmutableMap.of("PYTHONHASHSEED", "random"),
                      "-c",
                      "python.interpreter=" + py3,
                      "//:lib#py-default,default,compile"));
          Map<Path, Sha1HashCode> hashes = new HashMap<>();
          for (Path path :
              filesystem
                  .asView()
                  .getFilesUnderPath(dir.getPath(), EnumSet.noneOf(FileVisitOption.class))) {
            hashes.put(path, filesystem.computeSha1(path));
          }
          workspace.getBuildLog().assertTargetBuiltLocally(target);
          workspace.runBuckCommand("clean");
          return hashes;
        };

    // Verify that running the compilation multiple times yeilds the same results.
    assertThat(
        Stream.generate(buildAndGetHashes.asSupplier())
            .limit(10)
            .distinct()
            .collect(Collectors.toList()),
        Matchers.hasSize(1));
  }
}
