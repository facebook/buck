/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.ZipArchive;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;

public class JavadocTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void shouldCreateJavadocs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "javadocs", tmp);
    workspace.setUp();

    Path javadocJar = workspace.buildAndReturnOutput("//:lib#doc");

    assertTrue(Files.exists(javadocJar));
    try (ZipArchive zipArchive = new ZipArchive(javadocJar, false)) {
      Set<String> allFileNames = zipArchive.getFileNames();

      // Make sure we have an entry for a source file and an index.html
      assertTrue(allFileNames.contains("index.html"));
      assertTrue(allFileNames.contains("com/example/A.html"));
    }
  }

  @Test
  public void shouldCreateAnEmptyJarIfThereAreNoSources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "javadocs", tmp);
    workspace.setUp();

    Path javadocJar = workspace.buildAndReturnOutput("//:empty-lib#doc");

    assertTrue(Files.exists(javadocJar));
    try (ZipArchive zipArchive = new ZipArchive(javadocJar, false)) {
      Set<String> allFileNames = zipArchive.getFileNames();

      // Make sure we have an entry for a source file and an index.html
      assertTrue(allFileNames.isEmpty());
    }
  }
}
