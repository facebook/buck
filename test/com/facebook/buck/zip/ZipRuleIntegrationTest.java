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

package com.facebook.buck.zip;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class ZipRuleIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void shouldZipSources() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "zip-rule",
        tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:ziptastic");

    ZipInspector inspector = new ZipInspector(zip);
    inspector.assertFileExists("cake.txt");
    inspector.assertFileExists("beans/cheesy.txt");
  }

  @Test
  public void shouldUnpackContentsOfASrcJar() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "zip-rule",
        tmp);
    workspace.setUp();

    Path zip = workspace.buildAndReturnOutput("//example:unrolled");

    ZipInspector inspector = new ZipInspector(zip);
    inspector.assertFileExists("menu.txt");
  }
}
