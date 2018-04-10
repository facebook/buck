/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * See https://github.com/facebook/buck/issues/1830 for background.
 *
 * Integration test to verify:
 * 1. Java tests and java binaries access parameters in the same order.
 * 2. Order of the resolution.
 * Changing resolution order is breaking change and this test will catch it.
 */
public class JavaOrderingIntegrationTest extends AbiCompilationModeTest {

  private static final String JAVA_RESOLUTION_ORDER = Joiner.on(",").join(
      "1-2.txt:1",
      "1-3.txt:1",
      "1-4.txt:1",
      "1-5.txt:1",
      "2-3.txt:2",
      "2-4.txt:2",
      "2-5.txt:2",
      "3-4.txt:3",
      "3-5.txt:3",
      "4-5.txt:4");

  private static final String TEST_OUTPUT_MARKER = "===+===";

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  /**
   * Verifies that java Binary always see resources in lexicographic ordering.
   */
  @Test
  public void testJavaBinaryDuplicateResolutionOrdering() throws IOException {
    ProjectWorkspace workspace = setUpProjectWorkspace();
    Path path = workspace.buildAndReturnOutput("//buildables:bin");
    try (ZipFile zip = new ZipFile(path.toString())) {
      Assert.assertEquals("Expecting META-INF + 10 x-y.txt resources", 12, zip.stream().count());
      StringBuilder resources = new StringBuilder();
      for (int i = 1; i < 6; i++ ) {
        for (int j = i + 1; j < 6; j++ ) {
          String resource = i + "-" + j + ".txt";
          InputStream inputStream = zip.getInputStream(zip.getEntry(resource));
          Integer content =
              Integer.parseInt(String.valueOf((char)(new InputStreamReader(inputStream)).read()));
          inputStream.close();
          resources.append(resource + ":" + content + ",");
        }
      }
      resources.deleteCharAt(resources.length() - 1);
      Assert.assertEquals(
          "java_binary is expected to resolve conflicts in dependency in guaranteed order:",
          JAVA_RESOLUTION_ORDER,
          resources.toString());
    }
  }

  /**
   * Verifies that java Test always see resources in lexicographic ordering.
   */
  @Test
  public void testJavaTestDuplicateResolutionOrdering() throws IOException {
    ProjectWorkspace workspace = setUpProjectWorkspace();
    ProcessResult result = workspace.runBuckCommand("test", "//buildables:test");
    result.assertTestFailure();
    String stderr = result.getStderr();
    Assert.assertTrue(
        "Expecting test to contain payload start marker, instead failed with " + stderr,
        stderr.contains(TEST_OUTPUT_MARKER));
    String markedOutput =
        stderr.substring(stderr.indexOf(TEST_OUTPUT_MARKER) + TEST_OUTPUT_MARKER.length());
    Assert.assertTrue(
        "Expecting test to contain payload end marker, instead marked suffix is " + markedOutput,
        stderr.contains(TEST_OUTPUT_MARKER));
    markedOutput = markedOutput.substring(0, markedOutput.indexOf(TEST_OUTPUT_MARKER));
    Assert.assertEquals(JAVA_RESOLUTION_ORDER, markedOutput);
  }

  /**
   * Verifies class path audit shows classpath in lexicographic order for java binary.
   */
  @Test
  public void testJavaBinaryClasspath() throws IOException {
    ProjectWorkspace workspace = setUpProjectWorkspace();
    testClassPathAudit(workspace, "//buildables:bin");
  }

  /**
   * Verifies class path audit shows classpath in lexicographic order for java test.
   */
  @Test
  public void testJavaTestClasspath() throws IOException {
    ProjectWorkspace workspace = setUpProjectWorkspace();
    testClassPathAudit(workspace, "//buildables:test");
  }

  private void testClassPathAudit(
      ProjectWorkspace workspace, String target) throws IOException {
    ProcessResult result = workspace.runBuckCommand("audit", "classpath", target);
    result.assertSuccess();
    String[] classpathEntries = result.getStdout().split("\n");
    String[] sorted = Arrays.copyOf(classpathEntries, classpathEntries.length);
    Arrays.sort(sorted);
    Assert.assertArrayEquals("Expecting audit entries to be sorted", sorted, classpathEntries);
  }

  private ProjectWorkspace setUpProjectWorkspace() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_dep_ordering", tmp);
    setWorkspaceCompilationMode(workspace);
    workspace.setUp();
    return workspace;
  }
}
