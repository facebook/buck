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
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.impl.TargetConfigurationHasher;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.windowsfs.WindowsFS;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.RegexMatcher;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ThriftRuleKeyDeserializer;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ObjectArrays;
import com.google.common.io.MoreFiles;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.thrift.TException;
import org.hamcrest.Matchers;
import org.hamcrest.junit.MatcherAssert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class BuildCommandIntegrationTest {
  private static final ImmutableMap<String, String[]> SHOW_OUTPUT_TO_SHOW_OUTPUTS =
      ImmutableMap.of(
          "--show-output",
          new String[] {"--show-outputs"},
          "--show-full-output",
          new String[] {"--show-outputs", "--output-format", "full"},
          "--show-json-output",
          new String[] {"--show-outputs", "--output-format", "json"},
          "--show-full-json-output",
          new String[] {"--show-outputs", "--output-format", "full_json"});

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TemporaryPaths tmp2 = new TemporaryPaths();
  @Rule public ExpectedException expectedThrownException = ExpectedException.none();

  @Parameterized.Parameters
  public static Collection<Object> data() {
    return Arrays.asList(new Object[] {false, true});
  }

  @Parameterized.Parameter public boolean useShowOutputs;

  private ProjectWorkspace workspace;

  @Test
  public void justBuild() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    workspace.runBuckBuild("--just-build", "//:bar", "//:foo", "//:ex ample").assertSuccess();
    assertThat(
        workspace.getBuildLog().getAllTargets(),
        Matchers.contains(BuildTargetFactory.newInstance("//:bar")));
  }

  @Test
  public void justBuildWithOutputLabel() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp).setUp();
    workspace
        .runBuckBuild("--just-build", "//:bar[label]", "//:foo", "//:ex ample")
        .assertSuccess();

    // The entire "//:bar" rule is built, not just the artifacts associated with "//:bar[label]"
    assertThat(
        workspace.getBuildLog().getAllTargets(),
        Matchers.contains(BuildTargetFactory.newInstance("//:bar")));
  }

  @Test
  public void showOutput() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    String[] args = getCommandArgsForShowOutputOrShowOutputs("--show-output", "//:bar");
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
            .runBuckBuild("--show-outputs", "//:bar_with_multiple_outputs[output1]")
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
            .runBuckBuild("--show-outputs", "//:bar_with_multiple_outputs[output2]")
            .assertSuccess();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            String.format("//:bar_with_multiple_outputs[output2] %s", expectedPath2)));
  }

  @Test
  public void showOutputsForMultipleDefaultOutputs() throws IOException {
    // This isn't supported yet. Assert that this fails with the right error message
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckBuild("--show-outputs", "//:bar_with_multiple_outputs").assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("//:bar_with_multiple_outputs"));
    assertThat(result.getStdout(), Matchers.not(Matchers.containsString("buck-out")));
  }

  @Test
  public void showFullOutput() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();

    String[] args = getCommandArgsForShowOutputOrShowOutputs("--show-full-output", "//:bar");
    ProcessResult runBuckResult = workspace.runBuckBuild(args);

    runBuckResult.assertSuccess();
    Path expectedRootDirectory = tmp.getRoot();
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
        AbsPath.of(tmp.getRoot())
            .resolve(
                getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "bar"));
    AbsPath expectedPath2 =
        AbsPath.of(tmp.getRoot())
            .resolve(
                getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "baz"));

    ProcessResult runBuckResult =
        workspace
            .runBuckBuild(
                "--show-outputs",
                "--output-format",
                "full",
                "//:bar_with_multiple_outputs[output1]")
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
            .runBuckBuild(
                "--show-outputs",
                "--output-format",
                "full",
                "//:bar_with_multiple_outputs[output2]")
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

    String[] args =
        getCommandArgsForShowOutputOrShowOutputs(
            "--show-json-output", "//:foo", "//:bar", "//:ex ample");
    ProcessResult runBuckResult = workspace.runBuckBuild(args);

    runBuckResult.assertSuccess();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            String.format(
                "\"//:bar\" : \"%s/bar\",\n  \"//:ex ample\" : \"%s/example\",\n  \"//:foo\" : \"%s/foo\"\n}",
                BuildTargetPaths.getGenPath(
                    filesystem, BuildTargetFactory.newInstance("//:bar"), "%s"),
                BuildTargetPaths.getGenPath(
                    filesystem, BuildTargetFactory.newInstance("//:ex ample"), "%s"),
                BuildTargetPaths.getGenPath(
                    filesystem, BuildTargetFactory.newInstance("//:foo"), "%s"))));
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
            .runBuckBuild(
                "--show-outputs",
                "--output-format",
                "json",
                "//:bar_with_multiple_outputs[output1]")
            .assertSuccess();
    JsonNode observed =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(runBuckResult.getStdout()));

    JsonNode path = observed.get("//:bar_with_multiple_outputs[output1]");
    assertEquals(expectedPath1.toString(), path.asText());
    assertNull(observed.get("//:bar_with_multiple_outputs[output2]"));

    runBuckResult =
        workspace
            .runBuckBuild(
                "--show-outputs",
                "--output-format",
                "json",
                "//:bar_with_multiple_outputs[output2]")
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

    String[] args =
        getCommandArgsForShowOutputOrShowOutputs(
            "--show-full-json-output", "//:bar", "//:foo", "//:ex ample");
    ProcessResult runBuckResult = workspace.runBuckBuild(args);

    runBuckResult.assertSuccess();
    Path expectedRootDirectory = tmp.getRoot();
    assertThat(
        runBuckResult.getStdout(),
        Matchers.containsString(
            String.format(
                "{\n  \"//:bar\" : \"%s/bar\",\n  \"//:ex ample\" : \"%s/example\",\n  \"//:foo\" : \"%s/foo\"\n}",
                expectedRootDirectory.resolve(
                    BuildTargetPaths.getGenPath(
                        projectFilesystem, BuildTargetFactory.newInstance("//:bar"), "%s")),
                expectedRootDirectory.resolve(
                    BuildTargetPaths.getGenPath(
                        projectFilesystem, BuildTargetFactory.newInstance("//:ex ample"), "%s")),
                expectedRootDirectory.resolve(
                    BuildTargetPaths.getGenPath(
                        projectFilesystem, BuildTargetFactory.newInstance("//:foo"), "%s")))));
  }

  @Test
  public void showFullJsonOutputsForRulesWithMultipleOutputs() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    AbsPath expectedPath1 =
        AbsPath.of(tmp.getRoot())
            .resolve(
                getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "bar"));
    AbsPath expectedPath2 =
        AbsPath.of(tmp.getRoot())
            .resolve(
                getExpectedOutputPathRelativeToProjectRoot("//:bar_with_multiple_outputs", "baz"));

    ProcessResult runBuckResult =
        workspace
            .runBuckBuild(
                "--show-outputs",
                "--output-format",
                "full_json",
                "//:bar_with_multiple_outputs[output1]")
            .assertSuccess();
    JsonNode observed =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(runBuckResult.getStdout()));

    JsonNode path = observed.get("//:bar_with_multiple_outputs[output1]");
    assertEquals(expectedPath1.toString(), path.asText());
    assertNull(observed.get("//:bar_with_multiple_outputs[output2]"));

    runBuckResult =
        workspace
            .runBuckBuild(
                "--show-outputs",
                "--output-format",
                "full_json",
                "//:bar_with_multiple_outputs[output2]")
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
    String[] args =
        getCommandArgsForShowOutputOrShowOutputs("--show-output", "--show-rulekey", "//:bar");
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
  public void buckBuildAndCopyOutputFileWithBuildTargetThatSupportsIt() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    Path externalOutputs = tmp.newFolder("into-output");
    Path output = externalOutputs.resolve("the_example.jar");
    assertFalse(output.toFile().exists());
    workspace.runBuckBuild("//:example", "--out", output.toString()).assertSuccess();
    assertTrue(output.toFile().exists());

    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("com/example/Example.class");
  }

  @Test
  public void buckBuildAndCopyOutputFileIntoDirectoryWithBuildTargetThatSupportsIt()
      throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    Path outputDir = tmp.newFolder("into-output");
    assertEquals(0, MoreFiles.listFiles(outputDir).size());
    workspace.runBuckBuild("//:example", "--out", outputDir.toString()).assertSuccess();
    assertTrue(outputDir.toFile().isDirectory());
    assertEquals(1, MoreFiles.listFiles(outputDir).size());
    assertTrue(Files.isRegularFile(outputDir.resolve("example.jar")));
  }

  @Test
  public void buckBuildAndCopyOutputFileWithBuildTargetThatDoesNotSupportIt() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    Path externalOutputs = tmp.newFolder("into-output");
    Path output = externalOutputs.resolve("pylib.zip");
    assertFalse(output.toFile().exists());
    ProcessResult result = workspace.runBuckBuild("//:example_py", "--out", output.toString());
    result.assertFailure();
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "//:example_py does not have an output that is compatible with `buck build --out`"));
  }

  @Test
  public void buckBuildAndCopyOutputDirectoryIntoDirectoryWithBuildTargetThatSupportsIt()
      throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    Path outputDir = tmp.newFolder("into-output");
    assertEquals(0, MoreFiles.listFiles(outputDir).size());
    workspace.runBuckBuild("//:example_dir", "--out", outputDir.toString()).assertSuccess();
    assertTrue(Files.isDirectory(outputDir));
    File[] files = outputDir.toFile().listFiles();
    assertEquals(2, MoreFiles.listFiles(outputDir).size());
    assertTrue(Files.isRegularFile(outputDir.resolve("example.jar")));
    assertTrue(Files.isRegularFile(outputDir.resolve("example-2.jar")));
  }

  @Test
  public void lastOutputDir() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("-c", "build.create_build_output_symlinks_enabled=true", "//:bar");
    runBuckResult.assertSuccess();
    assertTrue(
        Files.exists(workspace.getBuckPaths().getLastOutputDir().toAbsolutePath().resolve("bar")));
  }

  @Test
  public void lastOutputDirForAppleBundle() throws IOException {
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "apple_app_bundle", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild(
            "-c", "build.create_build_output_symlinks_enabled=true", "//:DemoApp#dwarf-and-dsym");
    runBuckResult.assertSuccess();
    assertTrue(
        Files.exists(
            workspace.getBuckPaths().getLastOutputDir().toAbsolutePath().resolve("DemoApp.app")));
    assertTrue(
        Files.exists(
            workspace
                .getBuckPaths()
                .getLastOutputDir()
                .toAbsolutePath()
                .resolve("DemoAppBinary#apple-dsym,iphonesimulator-x86_64.dSYM")));
  }

  @Test
  public void writesBinaryRuleKeysToDisk() throws IOException, TException {
    Path logFile = tmp.newFile("out.bin");
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild(
            "--show-rulekey", "--rulekeys-log-path", logFile.toAbsolutePath().toString(), "//:bar");
    runBuckResult.assertSuccess();

    List<FullRuleKey> ruleKeys = ThriftRuleKeyDeserializer.readRuleKeys(logFile);
    // Three rules, they could have any number of sub-rule keys and contributors
    assertTrue(ruleKeys.size() >= 3);
    assertTrue(ruleKeys.stream().anyMatch(ruleKey -> ruleKey.name.equals("//:bar")));
  }

  @Test
  public void configuredBuckoutSymlinkinSubdirWorksWithoutCells() throws IOException {
    assumeFalse(Platform.detect() == WINDOWS);

    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild(
            "-c",
            "project.buck_out_compat_link=true",
            "-c",
            "project.buck_out=buck-out/mydir",
            "//:foo",
            "//:bar",
            "//:ex ample");
    runBuckResult.assertSuccess();

    assertTrue(Files.exists(workspace.getPath("buck-out/mydir/bin")));
    assertTrue(Files.exists(workspace.getPath("buck-out/mydir/gen")));

    Path buckOut = workspace.resolve("buck-out");
    assertEquals(
        buckOut.resolve("mydir/bin"),
        buckOut.resolve(Files.readSymbolicLink(buckOut.resolve("bin"))));
    assertEquals(
        buckOut.resolve("mydir/gen"),
        buckOut.resolve(Files.readSymbolicLink(buckOut.resolve("gen"))));
  }

  @Test
  public void enableEmbeddedCellHasOnlyOneBuckOut() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "multiple_cell_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("-c", "project.embedded_cell_buck_out_enabled=true", "//main/...");
    runBuckResult.assertSuccess();

    assertTrue(Files.exists(workspace.getPath("buck-out/cells/cxx")));
    assertTrue(Files.exists(workspace.getPath("buck-out/cells/java")));

    assertFalse(Files.exists(workspace.getPath("cxx/buck-out")));
    assertFalse(Files.exists(workspace.getPath("java/buck-out")));
  }

  @Test
  public void testFailsIfNoTargetsProvided() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build");
    result.assertExitCode(null, ExitCode.COMMANDLINE_ERROR);
    MatcherAssert.assertThat(
        result.getStderr(),
        Matchers.containsString(
            "Must specify at least one build target. See https://buck.build/concept/build_target_pattern.html"));
  }

  @Test
  public void handlesRelativeTargets() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();

    Path absolutePath = workspace.buildAndReturnOutput("//subdir1/subdir2:bar");

    workspace.setRelativeWorkingDirectory(Paths.get("subdir1"));

    Path subdirRelativePath = workspace.buildAndReturnOutput("subdir2:bar");

    Path subdirAbsolutePath = workspace.buildAndReturnOutput("//subdir1/subdir2:bar");

    assertEquals(absolutePath, subdirAbsolutePath);
    assertEquals(absolutePath, subdirRelativePath);
  }

  @Test
  public void canBuildAndUseRelativePathsFromWithinASymlinkedDirectory() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();

    assertFalse(tmp.getRoot().startsWith(tmp2.getRoot()));
    assertFalse(tmp2.getRoot().startsWith(tmp.getRoot()));

    Path dest = tmp2.getRoot().resolve("symlink_subdir").toAbsolutePath();
    Path relativeDest = tmp.getRoot().relativize(dest);

    MorePaths.createSymLink(new WindowsFS(), dest, tmp.getRoot());

    workspace.setRelativeWorkingDirectory(relativeDest);

    Path absolutePath = workspace.buildAndReturnOutput("//subdir1/subdir2:bar");

    workspace.setRelativeWorkingDirectory(relativeDest.resolve("subdir1"));

    Path subdirAbsolutePath = workspace.buildAndReturnOutput("//subdir1/subdir2:bar");
    Path subdirRelativePath = workspace.buildAndReturnOutput("subdir2:bar");

    assertEquals(absolutePath, subdirAbsolutePath);
    assertEquals(absolutePath, subdirRelativePath);
  }

  @Test
  public void printsAFriendlyErrorWhenRelativePathDoesNotExistInPwdButDoesInRoot()
      throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();

    String expectedWhenExists =
        "%s references a non-existent directory subdir1/subdir3 when run from subdir1";
    String expectedWhenNotExists = "%s references non-existent directory subdir1/subdir4";

    workspace.setRelativeWorkingDirectory(Paths.get("subdir1"));
    Files.createDirectories(tmp.getRoot().resolve("subdir3").resolve("something"));

    String recursiveTarget = workspace.runBuckBuild("subdir3/...").assertFailure().getStderr();
    String packageTarget = workspace.runBuckBuild("subdir3:").assertFailure().getStderr();
    String specificTarget = workspace.runBuckBuild("subdir3:target").assertFailure().getStderr();
    String noRootDirRecursiveTarget =
        workspace.runBuckBuild("subdir4/...").assertFailure().getStderr();
    String noRootDirPackageTarget = workspace.runBuckBuild("subdir4:").assertFailure().getStderr();
    String noRootDirSpecificTarget =
        workspace.runBuckBuild("subdir4:target").assertFailure().getStderr();

    assertThat(
        recursiveTarget, Matchers.containsString(String.format(expectedWhenExists, "subdir3/...")));
    assertThat(
        packageTarget, Matchers.containsString(String.format(expectedWhenExists, "subdir3:")));
    assertThat(
        specificTarget,
        Matchers.containsString(String.format(expectedWhenExists, "subdir3:target")));

    assertThat(
        noRootDirRecursiveTarget,
        Matchers.containsString(String.format(expectedWhenNotExists, "subdir4/...")));
    assertThat(
        noRootDirPackageTarget,
        Matchers.containsString(String.format(expectedWhenNotExists, "subdir4:")));
    assertThat(
        noRootDirSpecificTarget,
        Matchers.containsString(String.format(expectedWhenNotExists, "subdir4:target")));
  }

  @RuleArg
  abstract static class AbstractThrowInConstructorArg implements BuildRuleArg {}

  private static class ThrowInConstructor
      implements DescriptionWithTargetGraph<ThrowInConstructorArg> {

    @Override
    public Class<ThrowInConstructorArg> getConstructorArgType() {
      return ThrowInConstructorArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        ThrowInConstructorArg args) {
      throw new HumanReadableException("test test test");
    }
  }

  @Test
  public void ruleCreationError() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "rule_creation_error", tmp);
    workspace.setUp();
    workspace.setKnownRuleTypesFactoryFactory(
        (executor,
            pluginManager,
            sandboxExecutionStrategyFactory,
            knownConfigurationDescriptions) ->
            cell ->
                KnownNativeRuleTypes.of(
                    ImmutableList.of(new ThrowInConstructor()),
                    knownConfigurationDescriptions,
                    ImmutableList.of()));
    ProcessResult result = workspace.runBuckBuild(":qq");
    result.assertFailure();
    MatcherAssert.assertThat(result.getStderr(), Matchers.containsString("test test test"));
    MatcherAssert.assertThat(
        result.getStderr(), Matchers.not(Matchers.containsString("Exception")));
  }

  @Test
  public void includeTargetConfigHashInBuckOutWhenBuckConfigIsSet() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_out_config_target_hash", tmp);
    workspace.setUp();

    String[] args = getCommandArgsForShowOutputOrShowOutputs("--show-output", "//:binary");
    ProcessResult runBuckResult = workspace.runBuckBuild(args);

    BuildTarget target = BuildTargetFactory.newInstance("//:binary");
    runBuckResult.assertSuccess();
    String expected =
        BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s").toString()
            + ".jar";
    assertThat(
        expected,
        Matchers.matchesPattern(
            ".*" + TargetConfigurationHasher.hash(target.getTargetConfiguration()) + ".*"));
    assertEquals(runBuckResult.getStdout().trim(), "//:binary " + expected);
  }

  @Test
  public void matchBuckConfigValuesInConfigSettingInsideCompatibleWith() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "compatible_with_buck_config_values", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("section", "config", "true");
    assertThat(
        workspace
            .runBuckBuild("//:lib", "--target-platforms", "//:platform")
            .assertSuccess()
            .getStderr(),
        Matchers.containsString("BUILT 1/1 JOBS"));

    workspace.addBuckConfigLocalOption("section", "config", "false");
    assertThat(
        workspace
            .runBuckBuild("//:lib", "--target-platforms", "//:platform")
            .assertSuccess()
            .getStderr(),
        RegexMatcher.containsRegex("FINISHED IN .* 0/0 JOBS"));
  }

  private RelPath getExpectedOutputPathRelativeToProjectRoot(String targetName, String pathName)
      throws IOException {
    return workspace
        .getProjectFileSystem()
        .getRootPath()
        .relativize(
            workspace
                .getGenPath(BuildTargetFactory.newInstance(targetName), "%s__")
                .resolve(pathName));
  }

  @Test
  public void hardlinkOriginalBuckOutToHashedBuckOutWhenBuckConfigIsSet() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_out_config_target_hash", tmp);
    workspace.addBuckConfigLocalOption("project", "buck_out_links_to_hashed_paths", "hardlink");
    workspace.setUp();

    ProcessResult runBuckResult = workspace.runBuckBuild("//:binary");
    runBuckResult.assertSuccess();
    BuildTarget target = BuildTargetFactory.newInstance("//:binary");
    Path expected =
        workspace.resolve(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s")
                .resolveSibling("binary.jar"));
    Path hardlink = BuildPaths.removeHashFrom(expected, target).get();

    assertTrue("File not found " + expected.toString(), Files.exists(expected));
    assertTrue("File not found " + hardlink.toString(), Files.exists(hardlink));
    assertTrue("File is not a hardlink " + hardlink.toString(), Files.isRegularFile(hardlink));
  }

  @Test
  public void symlinkOriginalBuckOutToHashedBuckOutWhenBuckConfigIsSet() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_out_config_target_hash", tmp);
    workspace.addBuckConfigLocalOption("project", "buck_out_links_to_hashed_paths", "symlink");
    workspace.setUp();

    ProcessResult runBuckResult = workspace.runBuckBuild("//:binary");
    runBuckResult.assertSuccess();
    BuildTarget target = BuildTargetFactory.newInstance("//:binary");
    Path expected =
        workspace.resolve(
            BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s")
                .resolveSibling("binary.jar"));
    Path symlink = BuildPaths.removeHashFrom(expected, target).get();

    assertTrue("File not found " + expected.toString(), Files.exists(expected));
    assertTrue("File not found " + symlink.toString(), Files.exists(symlink));
    assertTrue("File is not a symlink " + symlink.toString(), Files.isSymbolicLink(symlink));
  }

  @Test
  public void canOverwriteExistingLinksToHashedBuckOut() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_out_config_target_hash", tmp);
    workspace.addBuckConfigLocalOption("project", "buck_out_links_to_hashed_paths", "hardlink");
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:binary");
    Path hardlink =
        BuildPaths.removeHashFrom(
                workspace.resolve(
                    BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s")
                        .resolveSibling("binary.jar")),
                target)
            .get();

    workspace.runBuckBuild("//:binary").assertSuccess();
    assertTrue(Files.exists(hardlink));
    workspace.runBuckBuild("//:binary").assertSuccess();
    assertTrue(Files.exists(hardlink));
  }

  @Test
  public void createSymlinkToHashedBuckOutForDirectoriesWhenHardlinkBuckConfigIsSet()
      throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_out_config_target_hash", tmp);
    workspace.addBuckConfigLocalOption("project", "buck_out_links_to_hashed_paths", "hardlink");
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:dir");
    Path symlink =
        BuildPaths.removeHashFrom(
                workspace.resolve(
                    BuildTargetPaths.getGenPath(workspace.getProjectFileSystem(), target, "%s")
                        .resolve("output")),
                target)
            .get();

    workspace.runBuckBuild("//:dir").assertSuccess();
    assertTrue(Files.isSymbolicLink(symlink));
  }

  @Test
  public void usesHashedBuckConfigOptionForRuleCaching() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "buck_out_config_target_hash", tmp);
    workspace.setUp();

    String fullyQualifiedName = "//:binary";

    workspace.runBuckBuild(fullyQualifiedName).assertSuccess();

    workspace.addBuckConfigLocalOption("project", "buck_out_include_target_config_hash", "false");

    assertThat(
        workspace.runBuckBuild("--show-output", fullyQualifiedName).assertSuccess().getStderr(),
        Matchers.containsString("100.0% CACHE MISS"));
  }

  @Test
  public void outputFormatCanOnlyBeUsedWithShowOutputs() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();

    ProcessResult runBuckResult =
        workspace
            .runBuckBuild("--show-output", "--output-format", "full", "//:bar")
            .assertFailure();
    assertThat(
        runBuckResult.getStderr(),
        Matchers.containsString("--output-format can only be used with --show-outputs"));
  }

  private String[] getCommandArgsForShowOutputOrShowOutputs(
      String showOutputCommand, String... args) {
    if (useShowOutputs) {
      return ObjectArrays.concat(
          SHOW_OUTPUT_TO_SHOW_OUTPUTS.get(showOutputCommand), args, String.class);
    }
    return ObjectArrays.concat(showOutputCommand, args);
  }
}
