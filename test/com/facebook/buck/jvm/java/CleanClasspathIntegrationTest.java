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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

/**
 * Integration test to verify that when a {@code java_library} rule is built, the classpath that is
 * used to build it does not contain any leftover artifacts from the previous build.
 */
public class CleanClasspathIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testJavaLibraryRuleDoesNotIncludeItsOwnOldOutputOnTheClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "classpath_corruption_regression", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    // Build //:example so that content is written to buck-out/gen/.
    BuildTarget target = BuildTargetFactory.newInstance("//:example");
    ProcessResult processResult1 =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());
    processResult1.assertSuccess();
    assertTrue(
        "example.jar should be written. This should not be on the classpath on the next build.",
        Files.isRegularFile(
            workspace.getPath(
                BuildTargets.getGenPath(filesystem, target, "lib__%s__output/example.jar"))));

    // Overwrite the existing BUCK file, redefining the java_library rule to exclude Bar.java from
    // its srcs.
    Path buildFile = workspace.getPath("BUCK");
    String newBuildFileContents =
        Joiner.on('\n')
            .join("java_library(", "  name = 'example',", "  srcs = [ 'Foo.java' ], ", ")");
    Files.write(buildFile, newBuildFileContents.getBytes(StandardCharsets.UTF_8));

    // Rebuilding //:example should fail even though Bar.class is in
    // buck-out/gen/lib__example__output/example.jar.
    ProcessResult processResult2 =
        workspace.runBuckCommand("build", target.getFullyQualifiedName());
    processResult2.assertFailure("Build should fail because Foo.java depends on Bar.java.");
  }
}
