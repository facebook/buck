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

package com.facebook.buck.shell;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;

public class RocksDBIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testSwitchingBackPrintsWarning() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "rocksdb_downgrade", temporaryFolder);
    workspace.setUp();

    workspace
        .runBuckCommand(
            "build", "--config", "build.metadata_storage=filesystem", "//:rocksdb_downgrade")
        .assertSuccess();

    workspace
        .runBuckCommand(
            "build", "--config", "build.metadata_storage=sqlite", "//:rocksdb_downgrade")
        .assertSuccess();

    workspace
        .runBuckCommand(
            "build", "--config", "build.metadata_storage=filesystem", "//:rocksdb_downgrade")
        .assertSuccess();
  }
}
