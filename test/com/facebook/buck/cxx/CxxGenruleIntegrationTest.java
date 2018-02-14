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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CxxGenruleIntegrationTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    assumeThat(Platform.detect(), Matchers.not(Matchers.is(Platform.WINDOWS)));
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_genrule", tmp);
    workspace.setUp();
  }

  @Test
  public void cppflags() throws IOException {
    workspace.replaceFileContents("BUCK", "@CMD@", "echo -- $(cppflags :c)");
    Path output = workspace.buildAndReturnOutput("//:rule#default");
    assertThat(workspace.getFileContents(output), Matchers.containsString("-DC_CFLAG"));
  }

  @Test
  public void cxxppflags() throws IOException {
    workspace.replaceFileContents("BUCK", "@CMD@", "echo -- $(cxxppflags :c)");
    Path output = workspace.buildAndReturnOutput("//:rule#default");
    assertThat(workspace.getFileContents(output), Matchers.containsString("-DC_CXXFLAG"));
  }

  @Test
  public void cppflagsTransitiveDeps() throws IOException {
    workspace.replaceFileContents("BUCK", "@CMD@", "echo -- $(cppflags :a)");
    Path output = workspace.buildAndReturnOutput("//:rule#default");
    assertThat(
        workspace.getFileContents(output),
        Matchers.allOf(Matchers.containsString("-DA_CFLAG"), Matchers.containsString("-DB_CFLAG")));
  }

  @Test
  public void cppflagsMultipleDeps() throws IOException {
    workspace.replaceFileContents("BUCK", "@CMD@", "echo -- $(cppflags :a :c)");
    Path output = workspace.buildAndReturnOutput("//:rule#default");
    assertThat(
        workspace.getFileContents(output),
        Matchers.allOf(Matchers.containsString("-DA_CFLAG"), Matchers.containsString("-DC_CFLAG")));
  }

  @Test
  public void cppflagsNoopBuild() throws IOException {
    workspace.replaceFileContents("BUCK", "@CMD@", "echo $(cppflags :header)");
    workspace.runBuckBuild("//:rule#default").assertSuccess();
    workspace.runBuckBuild("//:rule#default").assertSuccess();
    workspace.getBuildLog().assertNotTargetBuiltLocally("//:rule#default");
  }

  @Test
  public void cppflagsChangingHeaderCausesRebuild() throws IOException {
    workspace.replaceFileContents("BUCK", "@CMD@", "echo $(cppflags :header)");
    workspace.runBuckBuild("//:rule#default").assertSuccess();
    workspace.writeContentsToPath("#define HELLO", "real_header.h");
    workspace.runBuckBuild("//:rule#default").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:rule#default");
  }

  @Test
  public void headers() throws IOException {
    workspace.replaceFileContents(
        "BUCK", "@CMD@", "gcc -E $(cppflags :header) -include header.h - < /dev/null");
    workspace.runBuckBuild("//:rule#default").assertSuccess();
  }

  @Test
  public void ldflags() throws IOException {
    workspace.replaceFileContents("BUCK", "@CMD@", "echo -- $(ldflags-static :c)");
    Path output = workspace.buildAndReturnOutput("//:rule#default");
    assertThat(
        workspace.getFileContents(output),
        Matchers.allOf(Matchers.containsString("libc.a"), Matchers.containsString("-c-ld-flag")));
  }

  @Test
  public void ldflagsTransitiveDeps() throws IOException {
    workspace.replaceFileContents("BUCK", "@CMD@", "echo -- $(ldflags-static :a)");
    Path output = workspace.buildAndReturnOutput("//:rule#default");
    assertThat(
        workspace.getFileContents(output),
        Matchers.allOf(
            Matchers.containsString("liba.a"),
            Matchers.containsString("-a-ld-flag"),
            Matchers.containsString("libb.a"),
            Matchers.containsString("-b-ld-flag")));
  }

  @Test
  public void ldflagsMultipleDeps() throws IOException {
    workspace.replaceFileContents("BUCK", "@CMD@", "echo -- $(ldflags-static :a :c)");
    Path output = workspace.buildAndReturnOutput("//:rule#default");
    assertThat(
        workspace.getFileContents(output),
        Matchers.allOf(
            Matchers.containsString("liba.a"),
            Matchers.containsString("-a-ld-flag"),
            Matchers.containsString("libc.a"),
            Matchers.containsString("-c-ld-flag")));
  }

  @Test
  public void platformName() throws IOException {
    workspace.replaceFileContents("BUCK", "@CMD@", "echo -- $(platform-name)");
    Path output = workspace.buildAndReturnOutput("//:rule#default");
    assertThat(workspace.getFileContents(output), Matchers.containsString("default"));
  }
}
