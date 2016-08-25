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

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;

public class DistributedBuildIntegrationTest {
  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void canBuildJavaCode() throws Exception {
    final Path sourceFolderPath = temporaryFolder.newFolder("source");
    Path stateFilePath = temporaryFolder.getRoot().resolve("state_dump.bin");
    final Path destinationFolderPath = temporaryFolder.newFolder("destination");

    ProjectWorkspace sourceWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_java_target",
        sourceFolderPath);
    sourceWorkspace.setUp();

    sourceWorkspace.runBuckBuild(
        "//:lib",
        "--distributed",
        "--build-state-file",
        stateFilePath.toString())
        .assertSuccess();

    ProjectWorkspace destinationWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "empty",
        destinationFolderPath);
    destinationWorkspace.setUp();

    destinationWorkspace.runBuckDistBuildRun(
        "--build-state-file",
        stateFilePath.toString())
        .assertSuccess();
  }
}
