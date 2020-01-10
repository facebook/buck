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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.VersionStringComparator;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.nio.file.FileVisitOption;
import java.nio.file.Path;
import java.util.EnumSet;
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
    Path dir =
        filesystem.relativize(
            workspace.buildAndReturnOutput(
                "-c", "python.interpreter=" + py3, "//:lib#py-default,default,compile"));
    assertThat(
        filesystem.asView().getFilesUnderPath(dir, EnumSet.noneOf(FileVisitOption.class)).stream()
            .map(p -> PathFormatter.pathWithUnixSeparators(dir.relativize(p)))
            .collect(ImmutableList.toImmutableList()),
        Matchers.containsInAnyOrder(
            Matchers.matchesRegex("(__pycache__/)?foo(.cpython-3[0-9])?.pyc")));
  }
}
