/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.crosscell;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;

public class SourcePathResolutionIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testExportFileAsCxxSource() throws IOException {
    assumeThat(Platform.detect(), is(not(Platform.WINDOWS)));

    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(Paths.get("source-path/export-file"));
    ProjectWorkspace primary = cells.getFirst();
    primary.runBuckBuild("//:cxx_lib").assertSuccess();
    primary.runBuckCommand("run", "//:cxx_bin").assertSuccess();
  }

  @Test
  public void testExportFileAsAppleSource() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(Paths.get("source-path/export-file"));
    ProjectWorkspace primary = cells.getFirst();
    primary.runBuckBuild("//:apple_lib").assertSuccess();
    primary.runBuckCommand("run", "//:apple_bin").assertSuccess();
  }

  @Test
  public void testExportFileAsJavaSource() throws IOException {
    assumeThat(Platform.detect(), is(not(Platform.WINDOWS)));
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(Paths.get("source-path/export-file"));
    ProjectWorkspace primary = cells.getFirst();
    primary.runBuckBuild("//:java_lib").assertSuccess();
  }

  @Test
  public void testGenruleAsCxxSource() throws IOException {
    assumeThat(Platform.detect(), is(not(Platform.WINDOWS)));
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(Paths.get("source-path/genrule"));
    ProjectWorkspace primary = cells.getFirst();
    primary.runBuckBuild("//:cxx_lib").assertSuccess();
    primary.runBuckCommand("run", "//:cxx_bin").assertSuccess();
  }

  @Test
  public void testGenruleAsAppleSource() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(Paths.get("source-path/genrule"));
    ProjectWorkspace primary = cells.getFirst();
    primary.runBuckBuild("//:apple_lib").assertSuccess();
    primary.runBuckCommand("run", "//:apple_bin").assertSuccess();
  }

  @Test
  public void testGenruleAsJavaSource() throws IOException {
    assumeThat(Platform.detect(), is(not(Platform.WINDOWS)));
    Pair<ProjectWorkspace, ProjectWorkspace> cells = prepare(Paths.get("source-path/genrule"));
    ProjectWorkspace primary = cells.getFirst();
    primary.runBuckBuild("//:java_lib").assertSuccess();
  }

  private Pair<ProjectWorkspace, ProjectWorkspace> prepare(Path projectPath) throws IOException {
    return prepare(projectPath, "primary", "secondary");
  }

  private Pair<ProjectWorkspace, ProjectWorkspace> prepare(
      Path projectPath, String primaryCell, String secondaryCell) throws IOException {
    ProjectWorkspace primary = createWorkspace(projectPath.resolve(primaryCell).toString());
    ProjectWorkspace secondary = createWorkspace(projectPath.resolve(secondaryCell).toString());

    registerCell(primary, secondaryCell, secondary);

    return new Pair<>(primary, secondary);
  }

  private ProjectWorkspace createWorkspace(String scenarioName) throws IOException {
    Path tmpSubfolder = tmp.newFolder();
    ProjectWorkspace projectWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenarioName, tmpSubfolder);
    projectWorkspace.setUp();
    return projectWorkspace;
  }

  private void registerCell(
      ProjectWorkspace cellToModifyConfigOf,
      String cellName,
      ProjectWorkspace cellToRegisterAsCellName)
      throws IOException {
    TestDataHelper.overrideBuckconfig(
        cellToModifyConfigOf,
        ImmutableMap.of(
            "repositories",
            ImmutableMap.of(
                cellName, cellToRegisterAsCellName.getPath(".").normalize().toString())));
  }
}
