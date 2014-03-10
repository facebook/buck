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

package com.facebook.buck.java;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Integration test to verify that when a {@code java_library} rule is built, the classpath that is
 * used to build it does not contain any leftover artifacts from the previous build.
 */
public class CleanClasspathIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testJavaLibraryRuleDoesNotIncludeItsOwnOldOutputOnTheClasspath() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "classpath_corruption_regression", tmp);
    workspace.setUp();

    // Build //:example so that content is written to buck-out/gen/.
    ProcessResult processResult1 = workspace.runBuckCommand("build", "//:example");
    processResult1.assertSuccess();
    assertTrue(
        "example.jar should be written. This should not be on the classpath on the next build.",
        workspace.getFile("buck-out/gen/lib__example__output/example.jar").isFile());

    // Overwrite the existing BUCK file, redefining the java_library rule to exclude Bar.java from
    // its srcs.
    File buildFile = workspace.getFile("BUCK");
    String newBuildFileContents = Joiner.on('\n').join(
        "java_library(",
        "  name = 'example',",
        "  srcs = [ 'Foo.java' ], ",
        ")");
    Files.write(newBuildFileContents, buildFile, Charsets.UTF_8);

    // Rebuilding //:example should fail even though Bar.class is in
    // buck-out/gen/lib__example__output/example.jar.
    ProcessResult processResult2 = workspace.runBuckCommand("build", "//:example");
    processResult2.assertFailure("Build should fail because Foo.java depends on Bar.java.");
  }
}
