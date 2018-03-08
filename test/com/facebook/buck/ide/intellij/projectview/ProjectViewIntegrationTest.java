/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij.projectview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ProjectViewIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  private Path viewPath;
  private ProjectWorkspace workspace;

  @Before
  public void before() throws IOException {
    viewPath = Files.createTempDirectory("view");
  }

  @After
  public void after() throws IOException {
    MostFiles.deleteRecursivelyIfExists(viewPath);
    viewPath = null;
    workspace = null;
  }

  private ProcessResult runBuckProjectAndVerify(
      String folderWithTestData, boolean doSetup, String... commandArgs)
      throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    if (doSetup) {
      workspace =
          TestDataHelper.createProjectWorkspaceForScenario(
              this, folderWithTestData, temporaryFolder);
      workspace.setUp();
    }

    ProcessResult result =
        workspace.runBuckCommand(Lists.asList("project", commandArgs).toArray(new String[0]));
    result.assertSuccess("buck project should exit cleanly");

    return result;
  }

  // region Structural tests

  @Test
  public void testProjectView() throws IOException, InterruptedException {
    assertTrue(Files.exists(viewPath));
    assertEquals(0, viewPath.toFile().list().length);

    runBuckProjectAndVerify(
        "structuralTests", true, "--view", viewPath.toString(), "//testdata:testdata");

    assertTrue(Files.exists(viewPath));
    assertTrue(viewPath.toFile().list().length > 0);

    checkRootDotIml();
    checkBuckOut();
    checkResDirectory();
    checkJavaDirectory();
    checkDotIdeaDirectory();
  }

  private void checkRootDotIml() {
    Path rootDotIml = viewPath.resolve("root.iml");
    assertTrue(Files.exists(rootDotIml));
    // TODO(shemitz) Look inside the file!
  }

  private void checkBuckOut() {
    Path buckOut = viewPath.resolve("buck-out");
    assertTrue(Files.exists(buckOut));
    assertTrue(Files.isSymbolicLink(buckOut));
  }

  private void checkResDirectory() {
    Path resDirectory = viewPath.resolve("res");
    assertTrue(Files.exists(resDirectory));
    assertTrue(Files.isDirectory(resDirectory));

    // Everything under /res should be either a link (AndroidManifest.xml) or a directory of links
    walk(resDirectory, 2)
        .filter(child -> !child.equals(resDirectory))
        .forEach(
            child -> {
              if (Files.isDirectory(child)) {
                walk(child)
                    .filter(grandchild -> !grandchild.equals(child))
                    .forEach(grandchild -> assertTrue(Files.isSymbolicLink(grandchild)));
              } else {
                assertTrue(Files.isSymbolicLink(child));
              }
            });
  }

  private void checkJavaDirectory() {
    Path javaDirectory = viewPath.resolve("java");
    if (!Files.exists(javaDirectory)) {
      return;
    }
    assertTrue(Files.isDirectory(javaDirectory));

    // Everything under /java should be either a link or a directory of links
    walk(javaDirectory)
        .forEach(child -> assertTrue(Files.isDirectory(child) || Files.isSymbolicLink(child)));
  }

  private void checkDotIdeaDirectory() {
    Path dotIdeaDirectory = viewPath.resolve(".idea");
    assertTrue(Files.exists(dotIdeaDirectory));
    assertTrue(Files.isDirectory(dotIdeaDirectory));

    String[] children = dotIdeaDirectory.toFile().list();
    Set<String> childSet = new HashSet<>(children.length);
    for (String child : children) {
      childSet.add(child);
    }

    String[] expected =
        new String[] {
          "codeStyleSettings.xml", "libraries", "misc.xml", "modules.xml",
        };
    for (String expectation : expected) {
      assertTrue(childSet.contains(expectation));
    }
  }

  private static Stream<Path> walk(Path start, FileVisitOption... options) {
    try {
      return Files.walk(start, options);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Stream<Path> walk(Path start, int maxDepth, FileVisitOption... options) {
    try {
      return Files.walk(start, maxDepth, options);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // endregion Structural tests

  // region Update tests

  @Test
  public void testRefresh() throws IOException, InterruptedException {
    Path rootDotIml = viewPath.resolve("root.iml");
    Path buckOut = viewPath.resolve("buck-out");

    assertTrue(Files.exists(viewPath));
    assertFalse(Files.exists(rootDotIml));
    assertEquals(0, viewPath.toFile().list().length);

    runBuckProjectAndVerify(
        "structuralTests", true, "--view", viewPath.toString(), "//testdata:testdata");

    assertTrue(Files.exists(viewPath));
    assertTrue(Files.exists(rootDotIml));
    assertTrue(Files.exists(buckOut));
    assertTrue(Files.isSymbolicLink(buckOut));

    runBuckProjectAndVerify(
        "structuralTests", false, "--view", viewPath.toString(), "//testdata:testdata");

    assertTrue(Files.exists(viewPath));
    assertTrue(Files.exists(rootDotIml));
    assertTrue(Files.exists(buckOut));
    assertTrue(Files.isSymbolicLink(buckOut));
  }

  // endregion Update tests

}
