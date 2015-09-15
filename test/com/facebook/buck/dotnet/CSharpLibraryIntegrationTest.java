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

package com.facebook.buck.dotnet;

import static com.facebook.buck.dotnet.DotNetAssumptions.assumeCscIsAvailable;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public class CSharpLibraryIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void shouldCompileLibraryWithSystemProvidedDeps() throws IOException {
    assumeCscIsAvailable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "csc-tests",
        tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//src:simple");

    assertTrue(output.getFileName().toString().endsWith(".dll"));
    assertTrue("File doesn't seem to have been compiled", Files.size(output) > 0);
  }

  @Test
  public void shouldCompileLibraryWithAPrebuiltDependency() throws IOException {
    assumeCscIsAvailable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "csc-tests",
        tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//src:prebuilt");

    assertTrue(output.getFileName().toString().endsWith(".dll"));
  }

  @Test
  public void shouldBeAbleToEmbedResourcesIntoTheBuiltDll() throws IOException {
    assumeCscIsAvailable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "csc-tests",
        tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//src:embed");

    assertTrue(output.getFileName().toString().endsWith(".dll"));
    assertTrue("File doesn't seem to have been compiled", Files.size(output) > 0);
  }

  @Test
  public void shouldBeAbleToDependOnAnotherCSharpLibrary() throws IOException {
    assumeCscIsAvailable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "csc-tests",
        tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//src:dependent");

    result.assertSuccess();
  }

  @Test
  @Ignore
  public void shouldBeAbleToAddTheSameResourceToADllTwice() {
    fail("Implement me, please!");
  }
}
