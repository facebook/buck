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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.Zip;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class JavadocTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Test
  public void shouldCreateJavadocsWithoutAlsoCompilingCode() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "javadocs",
        tmp);
    workspace.setUp();

    Path javadocJar = workspace.buildAndReturnOutput("//:lib#doc");

    assertTrue(Files.exists(javadocJar));
    Zip zip = new Zip(javadocJar, false);
    Set<String> allFileNames = zip.getFileNames();

    // Make sure we have an entry for a source file and an index.html
    assertTrue(allFileNames.contains("index.html"));
    assertTrue(allFileNames.contains("com/example/A.html"));

    // And now make sure the library wasn't compiled
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand(
        "targets",
        "--show-output",
        "//:lib");
    result.assertSuccess();
    String outputLine =
        Splitter.on(CharMatcher.anyOf(System.lineSeparator()))
            .trimResults()
            .omitEmptyStrings()
            .splitToList(result.getStdout())
        .get(0);

    List<String> split = Splitter.on(' ').trimResults().splitToList(outputLine);
    assertEquals("//:lib", split.get(0));
    Path outputJar = workspace.resolve(split.get(1));

    assertFalse(Files.exists(outputJar));
  }

  @Test
  public void shouldCreateAnEmptyJarIfThereAreNoSources() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "javadocs",
        tmp);
    workspace.setUp();

    Path javadocJar = workspace.buildAndReturnOutput("//:empty-lib#doc");

    assertTrue(Files.exists(javadocJar));
    Zip zip = new Zip(javadocJar, false);
    Set<String> allFileNames = zip.getFileNames();

    // Make sure we have an entry for a source file and an index.html
    assertTrue(allFileNames.isEmpty());
  }
}
