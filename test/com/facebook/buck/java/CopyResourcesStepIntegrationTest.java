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
package com.facebook.buck.java;

import static org.hamcrest.Matchers.isIn;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class CopyResourcesStepIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testGeneratedResourceIsAlongsideClassFiles() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "generated_resources", temporaryFolder);
    workspace.setUp();

    // Build the java_library.
    String buildTarget = "//java/com/example:example";
    ProcessResult buildResult = workspace.runBuckBuild(buildTarget);
    buildResult.assertSuccess();

    // Use `buck targets` to find the output JAR file.
    ProcessResult outputFileResult = workspace.runBuckCommand(
        "targets", "--show_output", buildTarget);
    outputFileResult.assertSuccess();
    String pathToGeneratedJarFile = outputFileResult.getStdout().split(" ")[1].trim();
    File jarFile = workspace.getFile(pathToGeneratedJarFile);

    // Verify the entries in the output JAR file.
    assertTrue("Should exist: " + jarFile, jarFile.exists());
    Set<String> entries = Sets.newHashSet();
    try (JarFile jar = new JarFile(jarFile)) {
      for (Iterator<JarEntry> iter = Iterators.forEnumeration(jar.entries());
          iter.hasNext(); ) {
        JarEntry entry = iter.next();
        entries.add(entry.getName());
      }
    }
    assertThat("com/example/HelloWorld.class", isIn(entries));
    assertThat("com/example/res/helloworld.txt", isIn(entries));

    // Execute HelloWorld to ensure it can read the resource.
    ProcessResult runResult = workspace.runBuckCommand("run", "//java/com/example:HelloWorld");
    runResult.assertSuccess();
    // TODO(mbolin): Add this back in when https://phabricator.fb.com/D1247481 is submitted.
    // assertThat(runResult.getStdout(), containsString("hello world"));
  }
}
