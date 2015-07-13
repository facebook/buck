/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.gwt;

import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.environment.Platform;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class GwtBinaryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test(timeout = (2 * 60 * 1000)) // two minutes because CI times out on heavily loaded machines
  public void shouldBeAbleToBuildAGwtBinary() throws IOException {
    // Temporary as we investigate Mac OOM failures. t7687684
    assumeThat(Platform.detect(), Matchers.not(Platform.MACOS));
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "gwt_binary",
        tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:binary");

    result.assertSuccess();

    File zip = workspace.buildAndReturnOutput("//:binary");
    ZipInspector inspector = new ZipInspector(zip);
    inspector.assertFileExists("a/a.devmode.js");
    inspector.assertFileExists("a/a.nocache.js");
    inspector.assertFileExists("a/clear.cache.gif");
    inspector.assertFileExists("a/compilation-mappings.txt");
  }

}
