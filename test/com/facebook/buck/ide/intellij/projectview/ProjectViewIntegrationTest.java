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
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ProjectViewIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  private Path viewPath;

  @Before
  public void before() throws IOException {
    viewPath = Files.createTempDirectory("view");
    viewPath.toFile().deleteOnExit();
  }

  // region Structural tests

  @Test
  public void testProjectView() throws IOException, InterruptedException {
    assertTrue(Files.exists(viewPath));
    assertEquals(0, viewPath.toFile().list().length);

    runBuckProjectAndVerify(
        "structuralTests", "--view", viewPath.toString(), "//testdata:testdata");

    assertTrue(Files.exists(viewPath));
    assertTrue(viewPath.toFile().list().length > 0);

    checkRootDotIml();
    checkBuckOut();
    checkResDirectory();
    checkJavaDirectory();
    checkDotIdeaDirectory();
  }

  private ProcessResult runBuckProjectAndVerify(String folderWithTestData, String... commandArgs)
      throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, folderWithTestData, temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(Lists.asList("project", commandArgs).toArray(new String[0]));
    result.assertSuccess("buck project should exit cleanly");

    return result;
  }

  private void checkRootDotIml() {
    Path rootDotIml = viewPath.resolve("root.iml");
    assertTrue(Files.exists(rootDotIml));
    //TODO(shemitz) Look inside the file!
  }

  private void checkBuckOut() {
    Path buckOut = viewPath.resolve("buck-out");
    assertTrue(Files.exists(buckOut));
    assertTrue(Files.isSymbolicLink(buckOut));
  }

  private void checkResDirectory() throws IOException {
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

  // region View folder in repo?

  /** Tests that a view folder in the repo is detected and rejected */
  @Test
  public void testViewFolderInRepo() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "structuralTests", temporaryFolder);
    workspace.setUp();

    Path badViewPath = workspace.getPath("illegalViewDirectory");
    assertFalse(Files.exists(badViewPath));
    Files.createDirectory(badViewPath);
    assertTrue(Files.exists(badViewPath));
    assertEquals(0, badViewPath.toFile().list().length);

    ProcessResult result =
        workspace.runBuckCommand(
            "project", "--view", badViewPath.toString(), "//testdata:testdata");
    result.assertFailure("This should fail");
  }

  // endregion View folder in repo?
}
