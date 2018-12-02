/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.rust;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.cxx.ElfFile;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RustLinkerIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void ensureRustIsAvailable() {
    RustAssumptions.assumeRustIsConfigured();
  }

  @Test
  public void rustLinkerOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("run", "--config", "rust.linker=bad-linker", "//:xyzzy")
            .assertFailure()
            .getStderr(),
        Matchers.containsString("bad-linker"));
  }

  @Test
  public void rustLinkerCxxArgsOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    // rust.linker_args always passed through
    assertThat(
        workspace
            .runBuckCommand("run", "--config", "rust.linker_args=-lbad-linker-args", "//:xyzzy")
            .getStderr(),
        Matchers.containsString("library not found for -lbad-linker-args"));
  }

  @Test
  public void rustLinkerRustArgsOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    // rust.linker_args always passed through
    assertThat(
        workspace
            .runBuckCommand(
                "run",
                "--config",
                "rust.linker=/usr/bin/gcc", // need something that works
                "--config",
                "rust.linker_args=-lbad-linker-args",
                "//:xyzzy")
            .getStderr(),
        Matchers.containsString("library not found for -lbad-linker-args"));
  }

  @Test
  public void rustRuleLinkerFlagsOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//:xyzzy_linkerflags").assertFailure().getStderr(),
        Matchers.containsString("this-is-a-bad-option"));
  }

  @Test
  public void rustTestRuleLinkerFlagsOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_tests", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckBuild("//:test_success_linkerflags").assertFailure().getStderr(),
        Matchers.containsString("this-is-a-bad-option"));
  }

  @Test
  public void cxxLinkerOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("run", "--config", "cxx.ld=bad-linker", "//:xyzzy")
            .assertFailure()
            .getStderr(),
        Matchers.containsString("bad-linker"));
  }

  @Test
  public void cxxLinkerArgsOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    // default linker gets cxx.ldflags
    assertThat(
        workspace
            .runBuckCommand("run", "--config", "cxx.ldflags=-lbad-linker-args", "//:xyzzy")
            .assertFailure()
            .getStderr(),
        Matchers.containsString("library not found for -lbad-linker-args"));
  }

  @Test
  public void cxxLinkerArgsNoOverride() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    // cxx.ldflags not used if linker overridden
    workspace
        .runBuckCommand(
            "run",
            "--config",
            "rust.linker=/usr/bin/gcc", // want to set it to something that will work
            "--config",
            "cxx.ldflags=-lbad-linker-args",
            "//:xyzzy")
        .assertSuccess();
  }

  @Test
  public void cxxLinkerDependency() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand("run", "--config", "cxx.ld=//:generated_linker", "//:xyzzy")
        .assertSuccess();
  }

  @Test
  public void sharedLibraryHasDefaultSoname() throws IOException {
    assumeThat(Platform.detect(), Matchers.oneOf(Platform.FREEBSD, Platform.LINUX));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_library", tmp);
    workspace.setUp();

    Path libraryPath =
        workspace.buildAndReturnOutput("//:shared-library-with-default-soname#shared");
    String libraryFileName = libraryPath.getFileName().toString();
    assertThat(libraryFileName, Matchers.matchesPattern("liblibrary-.*\\.so"));
    assertThat(ElfFile.getSoname(libraryPath), Matchers.equalTo(Optional.of(libraryFileName)));
  }

  @Test
  public void binaryHasNoSoname() throws IOException {
    assumeThat(Platform.detect(), Matchers.oneOf(Platform.FREEBSD, Platform.LINUX));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "shared_library", tmp);
    workspace.setUp();

    Path binaryPath = workspace.buildAndReturnOutput("//:binary");
    assertThat(ElfFile.getSoname(binaryPath), Matchers.equalTo(Optional.empty()));
  }
}
