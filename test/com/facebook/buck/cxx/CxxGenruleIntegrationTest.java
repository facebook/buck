/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
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
  public void ldflagsStaticfilter() throws IOException {
    workspace.replaceFileContents(
        "BUCK", "@CMD@", "echo -- $(ldflags-static-filter ^.*prebuilt_c.* :dep_on_prebuilt_c)");
    Path output = workspace.buildAndReturnOutput("//:rule#default");
    assertThat(workspace.getFileContents(output), Matchers.containsString("dep_on_prebuilt_c"));
  }

  @Test
  public void ldflagsSharedfilter() throws IOException {
    workspace.replaceFileContents(
        "BUCK", "@CMD@", "echo -- $(ldflags-shared-filter ^.*prebuilt_c.* :dep_on_prebuilt_c)");
    Path output = workspace.buildAndReturnOutput("//:with_out_rule#default");
    assertThat(workspace.getFileContents(output), Matchers.containsString("dep_on_prebuilt_c"));
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

  @Test
  public void locationPlatformMacro() throws IOException {
    workspace.replaceFileContents(
        "BUCK", "@CMD@", "echo -- $(location-platform //:trivial shared)");
    Path trivialPath = workspace.buildAndReturnOutput("//:trivial#default,shared");
    Path output = workspace.buildAndReturnOutput("//:rule#default");
    assertThat(workspace.getFileContents(output), Matchers.containsString(trivialPath.toString()));
  }

  @Test
  public void namedOutputMapsToOutputPath() throws IOException {
    ProjectWorkspace testWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_genrule_outputs_map", tmp);
    testWorkspace.setUp();

    Path result = testWorkspace.buildAndReturnOutput("//:outputs_map#default[output1]");
    assertTrue(result.endsWith("out1.txt"));
    assertEquals("something1" + System.lineSeparator(), testWorkspace.getFileContents(result));

    result = testWorkspace.buildAndReturnOutput("//:outputs_map#default[output2]");
    assertTrue(result.endsWith("out2.txt"));
    assertEquals("another2" + System.lineSeparator(), testWorkspace.getFileContents(result));

    result = testWorkspace.buildAndReturnOutput("//:outputs_map#default");
    assertTrue(result.endsWith("default.txt"));
    assertEquals("defaultfoo" + System.lineSeparator(), testWorkspace.getFileContents(result));
  }

  @Test
  public void namedOutputInMultipleGroups() throws IOException {
    ProjectWorkspace testWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "cxx_genrule_named_output_groups", tmp);
    testWorkspace.setUp();

    Path result = testWorkspace.buildAndReturnOutput("//:named_output_groups#default[output1]");
    assertTrue(result.endsWith("out.txt"));
    assertEquals("something" + System.lineSeparator(), testWorkspace.getFileContents(result));

    result = testWorkspace.buildAndReturnOutput("//:named_output_groups#default[output2]");
    assertTrue(result.endsWith("out.txt"));
    assertEquals("something" + System.lineSeparator(), testWorkspace.getFileContents(result));

    result = testWorkspace.buildAndReturnOutput("//:named_output_groups#default");
    assertTrue(result.endsWith("out.txt"));
    assertEquals("something" + System.lineSeparator(), testWorkspace.getFileContents(result));
  }

  @Test
  public void errorOnRuleWithBothOutAndOuts() throws IOException {
    ProjectWorkspace testWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "cxx_genrule_incompatible_outattrs", tmp);
    testWorkspace.setUp();
    ProcessResult processResult = testWorkspace.runBuckBuild("//:binary#default");
    processResult.assertExitCode(ExitCode.BUILD_ERROR);
    assertTrue(
        processResult
            .getStderr()
            .contains("One and only one of 'out' or 'outs' must be present in cxx_genrule."));
  }

  @Test
  public void errorOnNameOutputsWithoutDefaultOuts() throws IOException {
    ProjectWorkspace testWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cxx_genrule_default_outs", tmp);
    testWorkspace.setUp();

    ProcessResult result =
        testWorkspace
            .runBuckBuild("//:target_without_default_outs#default[output1]")
            .assertExitCode(ExitCode.BUILD_ERROR);

    assertTrue(
        result
            .getStderr()
            .contains("default_outs must be present if outs is present in cxx_genrule"));
  }

  @Test
  // TODO: Remove this test once named ouput is supported in CxxLinkerFlagsExpander
  public void errorldflagsSharedfilterNoOut() throws IOException {
    workspace.replaceFileContents(
        "BUCK", "@CMD@", " -- $(ldflags-shared-filter ^.*prebuilt_c.* :dep_on_prebuilt_c)");
    ProcessResult result =
        workspace.runBuckBuild("//:without_out_rule#default").assertExitCode(ExitCode.BUILD_ERROR);

    assertTrue(
        result.getStderr().contains("Out path required in cxx_genrule for shared dynamic linking"));
  }

  @Test
  public void generatedHeader() {
    workspace.runBuckBuild("//:header_bin").assertSuccess();
  }

  @Test
  public void cxxGenruleInSrcsOfAnotherCxxGenrule() {
    workspace.runBuckBuild("//:header_bin2").assertSuccess();
  }
}
