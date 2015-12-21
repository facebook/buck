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

package com.facebook.buck.lua;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class LuaBinaryIntegrationTest {

  private ProjectWorkspace workspace;
  private Path lua;

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Before
  public void setUp() throws IOException {

    // We don't currently support windows.
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));

    // Verify that a Lua interpreter is available on the system.
    LuaBuckConfig config =
        new LuaBuckConfig(
            FakeBuckConfig.builder().build(),
            new ExecutableFinder());
    Optional<Path> luaOptional = config.getSystemLua();
    assumeTrue(luaOptional.isPresent());
    lua = luaOptional.get();

    // Setup the workspace.
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "lua_binary", tmp);
    workspace.setUp();
  }

  @Test
  public void stdout() throws Exception {
    workspace.writeContentsToPath("require 'os'; io.stdout:write('hello world')", "simple.lua");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", "//:simple").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        result.getStdout().trim(),
        Matchers.equalTo("hello world"));
  }

  @Test
  public void stderr() throws Exception {
    workspace.writeContentsToPath("require 'os'; io.stderr:write('hello world')", "simple.lua");
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", "//:simple").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        result.getStderr().trim(),
        Matchers.endsWith("hello world"));
  }

  @Test
  public void errorCode() throws Exception {
    workspace.writeContentsToPath("require 'os'\nos.exit(5)", "simple.lua");
    workspace.runBuckBuild("//:simple").assertSuccess();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("run", "//:simple");
    assertThat(result.getExitCode(), Matchers.equalTo(5));
  }

  @Test
  public void error() throws Exception {
    workspace.writeContentsToPath("blah blah garbage", "simple.lua");
    workspace.runBuckBuild("//:simple").assertSuccess();
    workspace.runBuckCommand("run", "//:simple").assertFailure();
  }

  @Test
  public void args() throws Exception {
    workspace.writeContentsToPath("for i=-1,#arg do print(arg[i]) end", "simple.lua");
    Path arg0 = workspace.buildAndReturnOutput("//:simple");

    // no args...
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("run", "//:simple").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        Splitter.on(System.lineSeparator())
            .splitToList(result.getStdout().trim()),
        Matchers.contains(
            ImmutableList.<Matcher<? super String>>of(
                Matchers.equalTo(lua.toString()),
                Matchers.endsWith(arg0.toString()))));

    // with args...
    result = workspace.runBuckCommand("run", "//:simple", "--", "hello", "world").assertSuccess();
    assertThat(
        result.getStdout() + result.getStderr(),
        Splitter.on(System.lineSeparator())
            .splitToList(result.getStdout().trim()),
        Matchers.contains(
            ImmutableList.<Matcher<? super String>>of(
                Matchers.equalTo(lua.toString()),
                Matchers.endsWith(arg0.toString()),
                Matchers.equalTo("hello"),
                Matchers.equalTo("world"))));
  }

}
