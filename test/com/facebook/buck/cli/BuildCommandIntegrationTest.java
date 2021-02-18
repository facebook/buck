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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.file.MorePaths;
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
import com.google.common.collect.ImmutableList;
import com.google.common.io.MoreFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.thrift.TException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildCommandIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public TemporaryPaths tmp2 = new TemporaryPaths();
  @Rule public ExpectedException expectedThrownException = ExpectedException.none();

  private ProjectWorkspace workspace;

  @Test
  public void justBuild() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    workspace.runBuckBuild("--just-build", "//:bar", "//:foo", "//:ex ample").assertSuccess();
    assertThat(
        workspace.getBuildLog().getAllTargets(),
        contains(
            BuildTargetFactory.newInstance("//:bar"),
            BuildTargetFactory.newInstance("//:touch"),
            BuildTargetFactory.newInstance("//:touch-lib")));
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
        contains(
            BuildTargetFactory.newInstance("//:bar"),
            BuildTargetFactory.newInstance("//:touch"),
            BuildTargetFactory.newInstance("//:touch-lib")));
  }

  @Test
  public void justBuildWithDefaultTargetPlatform() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    workspace
        .runBuckBuild("--just-build", "//:bar", "//:with-default-target-platform")
        .assertSuccess();
    assertThat(
        workspace.getBuildLog().getAllTargets(),
        contains(
            BuildTargetFactory.newInstance("//:bar"),
            BuildTargetFactory.newInstance("//:touch"),
            BuildTargetFactory.newInstance("//:touch-lib")));
  }

  @Test
  public void buckBuildAndCopyOutputFileWithBuildTargetThatSupportsIt() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    AbsPath externalOutputs = tmp.newFolder("into-output");
    AbsPath output = externalOutputs.resolve("the_example.jar");
    assertFalse(output.toFile().exists());
    workspace.runBuckBuild("//:example", "--out", output.toString()).assertSuccess();
    assertTrue(output.toFile().exists());

    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("com/example/Example.class");
  }

  @Test
  public void buckBuildAndCopyOutputFileWithNamedOutput() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    AbsPath externalOutputs = tmp.newFolder("into-output");
    AbsPath output = externalOutputs.resolve("out2.txt");
    assertFalse(output.toFile().exists());
    workspace
        .runBuckBuild("//:rule_with_multiple_outputs[output2]", "--out", output.toString())
        .assertSuccess();
    assertTrue(output.toFile().exists());
    assertTrue(new String(Files.readAllBytes(output.getPath()), UTF_8).contains("another"));
  }

  @Test
  public void buckBuildAndCopyOutputFileIntoDirectoryWithBuildTargetThatSupportsIt()
      throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    AbsPath outputDir = tmp.newFolder("into-output");
    assertEquals(0, MoreFiles.listFiles(outputDir.getPath()).size());
    workspace.runBuckBuild("//:example", "--out", outputDir.toString()).assertSuccess();
    assertTrue(outputDir.toFile().isDirectory());
    assertEquals(1, MoreFiles.listFiles(outputDir.getPath()).size());
    assertTrue(Files.isRegularFile(outputDir.resolve("example.jar").getPath()));
  }

  @Test
  public void buckBuildAndCopyOutputFileWithBuildTargetThatDoesNotSupportIt() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    AbsPath externalOutputs = tmp.newFolder("into-output");
    AbsPath output = externalOutputs.resolve("pylib.zip");
    assertFalse(output.toFile().exists());
    ProcessResult result = workspace.runBuckBuild("//:example_py", "--out", output.toString());
    result.assertFailure();
    assertThat(
        result.getStderr(),
        containsString(
            "//:example_py does not have an output that is compatible with `buck build --out`"));
  }

  @Test
  public void buckBuildAndCopyOutputDirectoryIntoDirectoryWithBuildTargetThatSupportsIt()
      throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "build_into", tmp);
    workspace.setUp();

    AbsPath outputDir = tmp.newFolder("into-output");
    assertEquals(0, MoreFiles.listFiles(outputDir.getPath()).size());
    workspace.runBuckBuild("//:example_dir", "--out", outputDir.toString()).assertSuccess();
    assertTrue(Files.isDirectory(outputDir.getPath()));
    assertEquals(2, MoreFiles.listFiles(outputDir.getPath()).size());
    assertTrue(Files.isRegularFile(outputDir.resolve("example.jar").getPath()));
    assertTrue(Files.isRegularFile(outputDir.resolve("example-2.jar").getPath()));

    // File in gen dir must be preserved after `--out` invocation
    Path exampleJarInGenDir =
        workspace
            .getGenPath(BuildTargetFactory.newInstance("//:example_dir"), "%s")
            .resolve("example_dir")
            .resolve("example.jar");

    assertTrue(exampleJarInGenDir.toString(), Files.exists(exampleJarInGenDir));
  }

  @Test
  public void lastOutputDir() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild("-c", "build.create_build_output_symlinks_enabled=true", "//:bar");
    runBuckResult.assertSuccess();
    AbsPath absLastOutputDir = workspace.getLastOutputDir();
    assertTrue(Files.exists(absLastOutputDir.resolve("bar").getPath()));
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
    assertTrue(Files.exists(workspace.getLastOutputDir().resolve("DemoApp.app").getPath()));
    assertTrue(
        Files.exists(
            workspace
                .getLastOutputDir()
                .resolve("DemoAppBinary#apple-dsym,iphonesimulator-x86_64.dSYM")
                .getPath()));
  }

  @Test
  public void writesBinaryRuleKeysToDisk() throws IOException, TException {
    AbsPath logFile = tmp.newFile("out.bin");
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
    workspace.setUp();
    ProcessResult runBuckResult =
        workspace.runBuckBuild(
            "--show-rulekey", "--rulekeys-log-path", logFile.toString(), "//:bar");
    runBuckResult.assertSuccess();

    List<FullRuleKey> ruleKeys = ThriftRuleKeyDeserializer.readRuleKeys(logFile);
    // Three rules, they could have any number of sub-rule keys and contributors
    assertTrue(ruleKeys.size() >= 3);
    assertTrue(ruleKeys.stream().anyMatch(ruleKey -> ruleKey.name.equals("//:bar")));
  }

  @Test
  public void configuredBuckoutSymlinkinSubdirWorksWithoutCells() throws IOException {
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
    ProcessResult runBuckResult = workspace.runBuckBuild("//main/...");
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
    assertThat(
        result.getStderr(),
        containsString(
            "Must specify at least one build target. See https://dev.buck.build/concept/build_target_pattern.html"));
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

    AbsPath dest = tmp2.getRoot().resolve("symlink_subdir");
    RelPath relativeDest = tmp.getRoot().relativize(dest);

    MorePaths.createSymLink(new WindowsFS(), dest, tmp.getRoot().getPath());

    workspace.setRelativeWorkingDirectory(relativeDest.getPath());

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
    Files.createDirectories(tmp.getRoot().resolve("subdir3").resolve("something").getPath());

    String recursiveTarget = workspace.runBuckBuild("subdir3/...").assertFailure().getStderr();
    String packageTarget = workspace.runBuckBuild("subdir3:").assertFailure().getStderr();
    String specificTarget = workspace.runBuckBuild("subdir3:target").assertFailure().getStderr();
    String noRootDirRecursiveTarget =
        workspace.runBuckBuild("subdir4/...").assertFailure().getStderr();
    String noRootDirPackageTarget = workspace.runBuckBuild("subdir4:").assertFailure().getStderr();
    String noRootDirSpecificTarget =
        workspace.runBuckBuild("subdir4:target").assertFailure().getStderr();

    assertThat(recursiveTarget, containsString(String.format(expectedWhenExists, "subdir3/...")));
    assertThat(packageTarget, containsString(String.format(expectedWhenExists, "subdir3:")));
    assertThat(specificTarget, containsString(String.format(expectedWhenExists, "subdir3:target")));

    assertThat(
        noRootDirRecursiveTarget,
        containsString(String.format(expectedWhenNotExists, "subdir4/...")));
    assertThat(
        noRootDirPackageTarget, containsString(String.format(expectedWhenNotExists, "subdir4:")));
    assertThat(
        noRootDirSpecificTarget,
        containsString(String.format(expectedWhenNotExists, "subdir4:target")));
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
    assertThat(result.getStderr(), containsString("test test test"));
    assertThat(result.getStderr(), not(containsString("Exception")));
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
        containsString("BUILT 1/1 JOBS"));

    workspace.addBuckConfigLocalOption("section", "config", "false");
    assertThat(
        workspace
            .runBuckBuild("//:lib", "--target-platforms", "//:platform")
            .assertSuccess()
            .getStderr(),
        RegexMatcher.containsRegex("FINISHED IN .* 0/0 JOBS"));
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
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(), target, "%s")
                .getPath()
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
            BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem().getBuckPaths(), target, "%s")
                .getPath()
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
                    BuildTargetPaths.getGenPath(
                            workspace.getProjectFileSystem().getBuckPaths(), target, "%s")
                        .getPath()
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
                    BuildTargetPaths.getGenPath(
                            workspace.getProjectFileSystem().getBuckPaths(), target, "%s")
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
        containsString("100.0% CACHE MISS"));
  }

  @Test
  public void outputLabelIsIncludedInRulekey() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "build_cache", tmp).setUp();
    String target = "//:my_file";

    // Build the target twice. Second build should be cached
    workspace.runBuckBuild(target);
    workspace.getBuildLog().assertTargetBuiltLocally(target);
    workspace.runBuckBuild(target);
    assertEquals(
        BuildRuleSuccessType.MATCHING_RULE_KEY,
        workspace.getBuildLog().getLogEntry(target).getSuccessType().get());

    // Change the target to depend on its original dep target, but now the dep has a different
    // output label
    workspace.replaceFileContents("BUCK", "outputs_map[output1]", "outputs_map[output2]");

    // Build the target twice again. First build should not be cached. Second build should be cached
    workspace.runBuckBuild(target);
    workspace.getBuildLog().assertTargetBuiltLocally(target);
    workspace.runBuckBuild(target);
    assertEquals(
        BuildRuleSuccessType.MATCHING_RULE_KEY,
        workspace.getBuildLog().getLogEntry(target).getSuccessType().get());

    // Change something about the dep target
    workspace.replaceFileContents("BUCK", "out2.txt", "new_out.txt");

    // Build the target twice again. First build should not be cached. Second build should be cached
    workspace.runBuckBuild(target);
    workspace.getBuildLog().assertTargetBuiltLocally(target);
    workspace.runBuckBuild(target);
    assertEquals(
        BuildRuleSuccessType.MATCHING_RULE_KEY,
        workspace.getBuildLog().getLogEntry(target).getSuccessType().get());
  }
}
