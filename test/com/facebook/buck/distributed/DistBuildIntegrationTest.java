/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.rules.Cell;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class DistBuildIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void canBuildJavaCode() throws Exception {
    final Path sourceFolderPath = temporaryFolder.newFolder("source");
    Path stateFilePath = temporaryFolder.getRoot().resolve("state_dump.bin");
    final Path destinationFolderPath = temporaryFolder.newFolder("destination");

    ProjectWorkspace sourceWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_java_target", sourceFolderPath);
    sourceWorkspace.setUp();

    sourceWorkspace
        .runBuckBuild("//:lib1", "--distributed", "--build-state-file", stateFilePath.toString())
        .assertSuccess();

    ProjectWorkspace destinationWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty", destinationFolderPath);
    destinationWorkspace.setUp();

    destinationWorkspace
        .runBuckDistBuildRun("--build-state-file", stateFilePath.toString())
        .assertSuccess();
  }

  @Test
  public void canBuildCrossCellWithSymlinksAndAbsPathTools() throws Exception {
    final Path sourceFolderPath = temporaryFolder.newFolder("source");
    final Path destinationFolderPath = temporaryFolder.newFolder("destination");
    final String scenario = "multi_cell_java_target";
    Path stateFilePath = temporaryFolder.getRoot().resolve("state_dump.bin");

    ProjectWorkspace mainCellWorkspace = setupCell(scenario, "main_cell", sourceFolderPath);
    setupCell(scenario, "secondary_cell", sourceFolderPath);
    ProjectWorkspace absPathWorkspace = setupCell(scenario, "abs_path_dir", sourceFolderPath);
    Cell mainCell = mainCellWorkspace.asCell();

    Path dJavaFileAbsPath = absPathWorkspace.asCell().getFilesystem().resolve("D.java");
    Path dJavaFileSymlinkAbsPath = mainCell.getFilesystem().resolve("D.java");
    mainCell.getFilesystem().createSymLink(dJavaFileSymlinkAbsPath, dJavaFileAbsPath, false);

    mainCellWorkspace
        .runBuckBuild("//:libA", "--distributed", "--build-state-file", stateFilePath.toString())
        .assertSuccess();

    ProjectWorkspace destinationWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty", destinationFolderPath);
    destinationWorkspace.setUp();

    destinationWorkspace
        .runBuckDistBuildRun("--build-state-file", stateFilePath.toString())
        .assertSuccess();
  }

  @Test
  public void preloadingMaterializesWhitelist() throws Exception {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);
    final Path sourceFolderPath = temporaryFolder.newFolder("source");
    Path stateFilePath = temporaryFolder.getRoot().resolve("state_dump.bin");
    final Path destinationFolderPath = temporaryFolder.newFolder("destination");

    ProjectWorkspace sourceWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "preloading_whitelist", sourceFolderPath);
    sourceWorkspace.setUp();

    sourceWorkspace
        .runBuckBuild("//:libA", "--distributed", "--build-state-file", stateFilePath.toString())
        .assertSuccess();

    ProjectWorkspace destinationWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty", destinationFolderPath);
    destinationWorkspace.setUp();

    destinationWorkspace
        .runBuckDistBuildRun("--build-state-file", stateFilePath.toString())
        .assertSuccess();
  }

  private ProjectWorkspace setupCell(String scenario, String cellSubDir, Path outputDir)
      throws IOException {
    Path cellPath = outputDir.resolve(cellSubDir);
    ProjectWorkspace sourceWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, scenario + "/" + cellSubDir, cellPath);
    sourceWorkspace.setUp();
    return sourceWorkspace;
  }
}
