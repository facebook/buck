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

package com.facebook.buck.cli;

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.impl.TargetConfigurationHasher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class BuildCommandShowOutputIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private RelPath getExpectedOutputPathRelativeToProjectRoot(String targetName, String pathName)
      throws IOException {
    return workspace
        .getProjectFileSystem()
        .getRootPath()
        .relativize(
            workspace
                .getGenPath(BuildTargetFactory.newInstance(targetName), "%s")
                .resolve(pathName));
  }

  @Test
  public void showOutput() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    String[] args = new String[] {"--show-output", "//:bar"};
    ProcessResult runBuckResult = workspace.runBuckBuild(args);
    runBuckResult.assertSuccess();
    assertThat(runBuckResult.getStdout(), Matchers.containsString("//:bar buck-out"));
  }

  @Test
  public void showOutputsForRulesWithMultipleOutputs() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    RelPath expectedPath1 =
        getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "bar");
    RelPath expectedPath2 =
        getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "baz");

    ProcessResult runBuckResult =
        workspace
            .runBuckBuild("--show-output", "//:bar_with_multiple_outputs[output1]")
            .assertSuccess();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            String.format("//:bar_with_multiple_outputs[output1] %s", expectedPath1)));
    assertFalse(
        runBuckResult
            .getStdout()
            .contains(String.format("//:bar_with_multiple_outputs[output2] %s", expectedPath2)));

    runBuckResult =
        workspace
            .runBuckBuild("--show-output", "//:bar_with_multiple_outputs[output2]")
            .assertSuccess();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            String.format("//:bar_with_multiple_outputs[output2] %s", expectedPath2)));
  }

  @Test
  public void showOutputsForMultipleDefaultOutputs() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    RelPath expectedPath =
        getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "baz");

    ProcessResult result =
        workspace.runBuckBuild("--show-output", "//:bar_with_multiple_outputs").assertSuccess();
    assertThat(
        result.getStdout(),
        Matchers.containsString(String.format("//:bar_with_multiple_outputs %s", expectedPath)));
  }

  @Test
  public void showFullOutput() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();

    String[] args = new String[] {"--show-full-output", "//:bar"};
    ProcessResult runBuckResult = workspace.runBuckBuild(args);

    runBuckResult.assertSuccess();
    AbsPath expectedRootDirectory = tmp.getRoot();
    String expectedOutputDirectory = expectedRootDirectory.resolve("buck-out/").toString();
    String stdout = runBuckResult.getStdout();
    assertThat(stdout, Matchers.containsString("//:bar "));
    assertThat(stdout, Matchers.containsString(expectedOutputDirectory));
  }

  @Test
  public void showFullOutputsForRulesWithMultipleOutputs() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    AbsPath expectedPath1 =
        tmp.getRoot()
            .resolve(
                getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "bar"));
    AbsPath expectedPath2 =
        tmp.getRoot()
            .resolve(
                getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "baz"));

    ProcessResult runBuckResult =
        workspace
            .runBuckBuild("--show-full-output", "//:bar_with_multiple_outputs[output1]")
            .assertSuccess();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            String.format("//:bar_with_multiple_outputs[output1] %s", expectedPath1)));
    assertFalse(
        runBuckResult
            .getStdout()
            .contains(String.format("//:bar_with_multiple_outputs[output2] %s", expectedPath2)));

    runBuckResult =
        workspace
            .runBuckBuild("--show-full-output", "//:bar_with_multiple_outputs[output2]")
            .assertSuccess();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            String.format("//:bar_with_multiple_outputs[output2] %s", expectedPath2)));
  }

  @Test
  public void showJsonOutput() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProjectFilesystem filesystem = workspace.getProjectFileSystem();

    String[] args = new String[] {"--show-json-output", "//:foo", "//:bar", "//:ex ample"};
    ProcessResult runBuckResult = workspace.runBuckBuild(args);

    runBuckResult.assertSuccess();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            String.format(
                "\"//:bar\" : \"%s/bar\",\n  \"//:ex ample\" : \"%s/example\",\n  \"//:foo\" : \"%s/foo\"\n}",
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:bar"), "%s"),
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:ex ample"), "%s"),
                BuildTargetPaths.getGenPath(
                    filesystem.getBuckPaths(), BuildTargetFactory.newInstance("//:foo"), "%s"))));
  }

  @Test
  public void showJsonOutputsForRulesWithMultipleOutputs() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    RelPath expectedPath1 =
        getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "bar");
    RelPath expectedPath2 =
        getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "baz");

    ProcessResult runBuckResult =
        workspace
            .runBuckBuild("--show-json-output", "//:bar_with_multiple_outputs[output1]")
            .assertSuccess();
    JsonNode observed =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(runBuckResult.getStdout()));

    JsonNode path = observed.get("//:bar_with_multiple_outputs[output1]");
    assertEquals(expectedPath1.toString(), path.asText());
    assertNull(observed.get("//:bar_with_multiple_outputs[output2]"));

    runBuckResult =
        workspace
            .runBuckBuild("--show-json-output", "//:bar_with_multiple_outputs[output2]")
            .assertSuccess();
    observed = ObjectMappers.READER.readTree(ObjectMappers.createParser(runBuckResult.getStdout()));

    path = observed.get("//:bar_with_multiple_outputs[output2]");
    assertEquals(expectedPath2.toString(), path.asText());
  }

  @Test
  public void showFullJsonOutput() throws IOException {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build/sub folder", tmp);
    workspace.setUp();
    ProjectFilesystem projectFilesystem = workspace.getProjectFileSystem();

    String[] args = new String[] {"--show-full-json-output", "//:bar", "//:foo", "//:ex ample"};
    ProcessResult runBuckResult = workspace.runBuckBuild(args);

    runBuckResult.assertSuccess();
    AbsPath expectedRootDirectory = tmp.getRoot();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            String.format(
                "{\n  \"//:bar\" : \"%s/bar\",\n  \"//:ex ample\" : \"%s/example\",\n  \"//:foo\" : \"%s/foo\"\n}",
                expectedRootDirectory.resolve(
                    BuildTargetPaths.getGenPath(
                        projectFilesystem.getBuckPaths(),
                        BuildTargetFactory.newInstance("//:bar"),
                        "%s")),
                expectedRootDirectory.resolve(
                    BuildTargetPaths.getGenPath(
                        projectFilesystem.getBuckPaths(),
                        BuildTargetFactory.newInstance("//:ex ample"),
                        "%s")),
                expectedRootDirectory.resolve(
                    BuildTargetPaths.getGenPath(
                        projectFilesystem.getBuckPaths(),
                        BuildTargetFactory.newInstance("//:foo"),
                        "%s")))));
  }

  @Test
  public void showFullJsonOutputsForRulesWithMultipleOutputs() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    AbsPath expectedPath1 =
        tmp.getRoot()
            .resolve(
                getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "bar"));
    AbsPath expectedPath2 =
        tmp.getRoot()
            .resolve(
                getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "baz"));

    ProcessResult runBuckResult =
        workspace
            .runBuckBuild("--show-full-json-output", "//:bar_with_multiple_outputs[output1]")
            .assertSuccess();
    JsonNode observed =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(runBuckResult.getStdout()));

    JsonNode path = observed.get("//:bar_with_multiple_outputs[output1]");
    assertEquals(expectedPath1.toString(), path.asText());
    assertNull(observed.get("//:bar_with_multiple_outputs[output2]"));

    runBuckResult =
        workspace
            .runBuckBuild("--show-full-json-output", "//:bar_with_multiple_outputs[output2]")
            .assertSuccess();
    observed = ObjectMappers.READER.readTree(ObjectMappers.createParser(runBuckResult.getStdout()));

    path = observed.get("//:bar_with_multiple_outputs[output2]");
    assertEquals(expectedPath2.toString(), path.asText());
  }

  @Test
  public void showRuleKey() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult = workspace.runBuckBuild("--show-rulekey", "//:bar");
    runBuckResult.assertSuccess();

    Pattern pattern = Pattern.compile("\\b[0-9a-f]{5,40}\\b"); // sha
    Matcher shaMatcher = pattern.matcher(runBuckResult.getStdout());
    assertThat(shaMatcher.find(), Matchers.equalTo(true));
    String shaValue = shaMatcher.group();
    assertThat(shaValue.length(), Matchers.equalTo(40));
    assertThat(runBuckResult.getStdout(), Matchers.containsString("//:bar " + shaValue));
  }

  @Test
  public void showRuleKeyAndOutput() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    String[] args = new String[] {"--show-output", "--show-rulekey", "//:bar"};
    ProcessResult runBuckResult = workspace.runBuckBuild(args);
    runBuckResult.assertSuccess();

    Pattern pattern = Pattern.compile("\\b[0-9a-f]{5,40}\\b"); // sha
    Matcher shaMatcher = pattern.matcher(runBuckResult.getStdout());
    assertThat(shaMatcher.find(), Matchers.equalTo(true));
    String shaValue = shaMatcher.group();
    assertThat(shaValue.length(), Matchers.equalTo(40));
    assertThat(
        runBuckResult.getStdout(), Matchers.containsString("//:bar " + shaValue + " buck-out"));
  }

  @Test
  public void includeTargetConfigHashInBuckOutWhenBuckConfigIsSet() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_out_config_target_hash", tmp);
    workspace.setUp();

    String[] args = new String[] {"--show-output", "//:binary"};
    ProcessResult runBuckResult = workspace.runBuckBuild(args);

    BuildTarget target = BuildTargetFactory.newInstance("//:binary");
    runBuckResult.assertSuccess();
    String expected =
        BuildTargetPaths.getGenPath(workspace.getProjectFileSystem().getBuckPaths(), target, "%s")
                .toString()
            + ".jar";
    assertThat(
        expected,
        Matchers.matchesPattern(
            ".*" + TargetConfigurationHasher.hash(target.getTargetConfiguration()) + ".*"));
    assertEquals(runBuckResult.getStdout().trim(), "//:binary " + expected);
  }
}
