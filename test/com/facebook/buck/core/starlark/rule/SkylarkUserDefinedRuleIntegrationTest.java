/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.rule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class SkylarkUserDefinedRuleIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void implementationFunctionIsCalledWithCtx() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implementation_is_called", tmp);

    workspace.setUp();

    workspace.runBuckBuild("//foo:bar").assertSuccess();
    ProcessResult failureRes = workspace.runBuckBuild("//foo:baz").assertFailure();
    assertThat(
        failureRes.getStderr(), Matchers.containsString("Expected to be called with name 'bar'"));
  }

  @Test
  public void implementationFunctionHasAccessToAttrs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "implementation_has_correct_attrs_in_ctx", tmp);

    workspace.setUp();

    workspace.runBuckBuild("//foo:").assertSuccess();
  }

  @Test
  public void printsProperly() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "print_works_in_impl", tmp);

    workspace.setUp();

    ProcessResult result = workspace.runBuckBuild("//foo:prints").assertSuccess();
    assertThat(
        result.getStderr(),
        Matchers.matchesPattern(
            Pattern.compile(
                ".*^DEBUG: \\S+defs.bzl:4:5: printing at debug level.*",
                Pattern.MULTILINE | Pattern.DOTALL)));
  }

  @Test
  public void implementationFunctionCanDeclareFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "implementation_declares_artifacts", tmp);

    workspace.setUp();

    workspace.runBuckBuild("//foo:valid_filename").assertSuccess();
  }

  @Test
  public void implementationDeclareFilesFailsOnInvalidFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "implementation_declares_artifacts", tmp);

    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//foo:not_a_path").assertFailure().getStderr(),
        Matchers.containsString("Invalid path"));
    assertThat(
        workspace.runBuckBuild("//foo:rejected_path").assertFailure().getStderr(),
        Matchers.containsString("attempted to traverse upwards"));
  }

  @Test
  public void implementationWritesFilesProperly() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "implementation_writes_files", tmp);

    workspace.setUp();
    DefaultProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    Path exePath =
        BuildPaths.getGenDir(filesystem, BuildTargetFactory.newInstance("//foo:exe"))
            .resolve("bar")
            .resolve("exe.sh");
    Path textPath =
        BuildPaths.getGenDir(filesystem, BuildTargetFactory.newInstance("//foo:text"))
            .resolve("bar")
            .resolve("text.txt");
    Path withSpacesPath =
        BuildPaths.getGenDir(filesystem, BuildTargetFactory.newInstance("//foo:with_spaces"))
            .resolve("bar")
            .resolve("with spaces.txt");

    assertFalse(filesystem.exists(exePath));
    assertFalse(filesystem.exists(textPath));

    workspace.runBuckBuild("//foo:exe", "//foo:text", "//foo:with_spaces").assertSuccess();

    assertEquals("exe content", filesystem.readFileIfItExists(filesystem.resolve(exePath)).get());
    assertEquals("text content", filesystem.readFileIfItExists(filesystem.resolve(textPath)).get());
    assertEquals(
        "with spaces content",
        filesystem.readFileIfItExists(filesystem.resolve(withSpacesPath)).get());
    // Executable works a bit differently on windows
    if (!Platform.isWindows()) {
      assertTrue(filesystem.isExecutable(exePath));
      assertFalse(filesystem.isExecutable(textPath));
      assertFalse(filesystem.isExecutable(withSpacesPath));
    }
  }
}
