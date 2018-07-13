/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class GoBinaryIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void ensureGoIsAvailable() throws IOException, InterruptedException {
    GoAssumptions.assumeGoCompilerAvailable();
  }

  @Test
  public void simpleBinary() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result =
        workspace.runCommand(workspace.resolve("buck-out/gen/xyzzy/xyzzy").toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), Matchers.containsString("Hello, world!"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void binaryWithAsm() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "asm", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//src/asm_test:bin");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("Sum is 6"));
  }

  @Test
  public void binaryWithAsmAndArchBuildTag() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "asm_with_arch_tag", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//src/asm_test:bin");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("Sum is 6"));
  }

  @Test
  public void binaryWithCgo() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//src/simple:bin");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("fmt: Go string"));
  }

  @Test
  public void binaryWithCgoAndGenruleAsSource() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//src/genrule:bin");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("fmt: Go string second"));
  }

  @Test
  public void binaryWithLibraryIncludingCgoLib() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//src/interdeps:bin");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("fmt: Go string"));
  }

  @Test
  public void buildAfterChangeWorks() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    workspace.writeContentsToPath(
        workspace.getFileContents("main.go") + "// this is a comment", "main.go");
    workspace.runBuckBuild("//:xyzzy").assertSuccess();
  }

  @Test
  public void binaryWithLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void binaryWithResources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_resources", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void vendoredLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "vendored_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void libraryWithPrefix() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "library_with_prefix", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void libraryWithPrefixAfterChange() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "library_with_prefix", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
    workspace.writeContentsToPath(
        workspace.getFileContents("messenger/printer/printer.go").replace('!', '?'),
        "messenger/printer/printer.go");
    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world?"));
  }

  @Test
  public void nonGoLibraryDepErrors() throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(Matchers.containsString("is not an instance of go_library"));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    workspace.runBuckCommand("run", "//:illegal_dep").assertFailure();
  }

  @Test
  public void exportedDeps() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exported_deps", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }

  @Test
  public void generatedSources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "generated_source", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }

  @Test
  public void generatedSourceDir() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "generated_source_dir", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }

  @Test
  public void emptySources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_sources", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }

  @Test
  public void rebuildingBinaryFromCacheWorksWithTransitiveDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "transitive_dep", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    // Do an initial built to warm the cache.
    workspace.runBuckBuild("//:main").assertSuccess();

    // Clean the build products, as we're going to test that pulling from cache works.
    workspace.runBuckCommand("clean", "--keep-cache");

    // Make a white-space only change -- enough to force a relink of the binary.
    workspace.replaceFileContents("main.go", "a.A()", " a.A()");

    // Run another build and verify it successfully built locally.
    workspace.runBuckBuild("//:main").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
  }

  @Test
  public void buildConstraints() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_constraints", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:family").assertSuccess();
  }

  @Test
  public void cgoIncludeHeaderFromSamePackage() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("run", "//src/mixed_with_c:bin");
    result.assertSuccess();
  }
}
