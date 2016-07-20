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

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryRoot;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Path;

public class DistributedBuildIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void canBuildJavaCode() throws Exception {
    final Path sourceFolderPath = temporaryFolder.newFolder("source").toPath();
    Path stateFilePath = temporaryFolder.getRootPath().resolve("state_dump.bin");
    final Path destinationFolderPath = temporaryFolder.newFolder("destination").toPath();


    ProjectWorkspace sourceWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_java_target",
        new TemporaryRoot() {
          @Override
          public Path getRootPath() {
            return sourceFolderPath;
          }
        });
    sourceWorkspace.setUp();

    sourceWorkspace.runBuckBuild(
        "//:lib",
        "--distributed",
        "--distributed-state-dump",
        stateFilePath.toString())
        .assertSuccess();

    ProjectWorkspace destinationWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "empty",
        new TemporaryRoot() {
          @Override
          public Path getRootPath() {
            return destinationFolderPath;
          }
        });
    destinationWorkspace.setUp();

    destinationWorkspace.runBuckBuild(
        "//:lib",
        "--distributed",
        "--distributed-state-dump",
        stateFilePath.toString())
        .assertSuccess();
  }
}
