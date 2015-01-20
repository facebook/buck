/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.Javac;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

public class FlavoredTargetsParserIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tempFolder = new DebuggableTemporaryFolder();

  @Test
  public void canBuildAnUnflavoredTarget() throws IOException {
    tempFolder.create();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "unflavored_build",
        tempFolder);
    workspace.setUp();

    File output = workspace.buildAndReturnOutput("//:example");

    // The output of the rule should be a normal jar. Verify that.
    assertTrue(output.getName().endsWith(".jar"));
    // And just in case we ever rename "src.zip" to "sources.jar" to look like a maven output.
    assertFalse(output.getName().endsWith(Javac.SRC_ZIP));
  }

  @Test
  public void canBuildAFlavoredTarget() throws IOException {
    tempFolder.create();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "unflavored_build",
        tempFolder);
    workspace.setUp();

    File output = workspace.buildAndReturnOutput("//:example#src");

    // The output of the rule should be a src zip. Verify that.
    assertTrue(output.getName(), output.getName().endsWith(Javac.SRC_ZIP));
  }

  @Test
  public void canBuildBothAFlavoredAndUnflavoredVersionOfTheSameTargetInTheSameBuild()
      throws IOException {
    tempFolder.create();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "unflavored_build",
        tempFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//:example", "//:example#src");
    result.assertSuccess();

    // Verify that both the src zip and the jar were created.
    result = workspace.runBuckCommand("targets", "--show_output", "//:example", "//:example#src");
    result.assertSuccess();
    String stdout = result.getStdout();
    List<String> paths = Splitter.on('\n').omitEmptyStrings().trimResults().splitToList(stdout);
    paths = Lists.reverse(paths);

    // There should be at least two paths output
    assertTrue(paths.toString(), paths.size() > 1);

    // The last two are the paths to the outputs
    File first = workspace.getFile(paths.get(0).split("\\s+")[1]);
    File second = workspace.getFile(paths.get(1).split("\\s+")[1]);

    assertTrue(first.exists());
    assertTrue(second.exists());
  }

  @Test
  public void canReferToFlavorsInBuildFiles() throws IOException {
    tempFolder.create();
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "flavored_build",   // NB: this is not the same as the other tests in this file!
        tempFolder);
    workspace.setUp();

    File output = workspace.buildAndReturnOutput("//:example");

    // Take a look at the contents of 'output'. It should be a source jar.
    try (Zip zip = new Zip(output, /* for writing? */ false)) {
      Set<String> fileNames = zip.getFileNames();

      assertTrue(fileNames.toString(), fileNames.contains("B.java"));
    }
  }
}
