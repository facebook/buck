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

package com.facebook.buck.shell;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class GenruleIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object> data() {
    return Arrays.asList(new Object[] {"", "_outs"});
  }

  @Parameterized.Parameter() public String targetSuffix;

  // When these tests fail, the failures contain buck output that is easy to confuse with the output
  // of the instance of buck that's running the test. This prepends each line with "> ".
  private String quoteOutput(String output) {
    output = output.trim();
    output = "> " + output;
    output = output.replace("\n", "\n> ");
    return output;
  }

  @Test
  public void testIfCommandExitsZeroThenGenruleFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_failing_command", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult =
        workspace.runBuckCommand("build", targetWithSuffix("//:fail"), "--verbose", "10");
    buildResult.assertFailure();

    /* We want to make sure we failed for the right reason. The expected should contain something
     * like the following:
     *
     * BUILD FAILED: //:fail failed with exit code 1:
     * (cd /tmp/junit12345/buck-out/gen/fail__srcs && /bin/bash -e -c 'false; echo >&2 hi')
     *
     * We should match all that, except for the specific temp dir.
     */

    // "(?s)" enables multiline matching for ".*". Parens have to be escaped.
    String outputPattern;

    if (Platform.detect() == Platform.WINDOWS) {
      outputPattern =
          "(?s).*Command failed with exit code 1\\..*"
              + "When running <\\(cd buck-out\\\\gen(\\\\[0-9a-zA-Z]+)?\\\\fail"
              + targetSuffix
              + "__srcs && .*\\\\buck-out\\\\tmp\\\\genrule-[0-9]*\\.cmd\\)>.*";

    } else {
      outputPattern =
          "(?s).*Command failed with exit code 1\\.(?s).*"
              + "When running <\\(cd buck-out/gen(/[0-9a-zA-Z]+)?/fail"
              + targetSuffix
              + "__srcs && /bin/bash -e .*/buck-out/tmp/genrule-[0-9]*\\.sh\\)>(?s).*";
    }

    assertTrue(
        "Unexpected output:\n" + quoteOutput(buildResult.getStderr()),
        buildResult.getStderr().matches(outputPattern));
  }

  @Test
  public void genruleWithEmptyOutParameterFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_empty_out", temporaryFolder);
    workspace.setUp();

    String targetName = targetWithSuffix("//:genrule");
    ProcessResult processResult = workspace.runBuckCommand("build", targetName);
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "The output path must be relative, simple, non-empty and not cross package boundary"));
  }

  @Test
  public void genruleWithAbsoluteOutParameterFails() throws IOException {
    // Note: the path in this genrule is not absolute on Windows.
    assumeThat(Platform.detect(), not(equalTo(Platform.WINDOWS)));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_absolute_out", temporaryFolder);
    workspace.setUp();

    String targetName = targetWithSuffix("//:genrule");
    ProcessResult processResult = workspace.runBuckCommand("build", targetName);
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "The output path must be relative, simple, non-empty and not cross package boundary"));
  }

  @Test
  public void genruleDirectoryOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_directory_output", temporaryFolder);
    workspace.setUp();
    workspace.enableDirCache();

    String targetName = targetWithSuffix("//:mkdir");
    workspace.runBuckCommand("build", targetName).assertSuccess();

    Path directoryPath = getOutputPath(workspace, targetName, "directory");
    assertTrue(Files.isDirectory(directoryPath));
    Path filePath = getOutputPath(workspace, targetName, "directory/file");

    assertThat(workspace.getFileContents(filePath), equalTo("something" + System.lineSeparator()));

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    assertFalse(Files.isDirectory(workspace.resolve("buck-out/gen")));
    // Retrieving the genrule output from the local cache should recreate the directory contents.
    workspace.runBuckCommand("build", targetName).assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache(targetName);
    assertTrue(Files.isDirectory(workspace.resolve(directoryPath)));
    assertThat(workspace.getFileContents(filePath), equalTo("something" + System.lineSeparator()));
  }

  @Test
  public void genruleWithBigCommand() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_big_command", temporaryFolder);
    workspace.setUp();

    String targetName = targetWithSuffix("//:big");
    workspace.runBuckCommand("build", targetName).assertSuccess();

    Path outputPath = getOutputPath(workspace, targetName, "file");
    assertTrue(Files.isRegularFile(outputPath));

    int stringSize = 1000;

    StringBuilder expectedOutput = new StringBuilder();
    for (int i = 0; i < stringSize; ++i) {
      expectedOutput.append("X");
    }
    expectedOutput.append(System.lineSeparator());
    assertThat(workspace.getFileContents(outputPath), equalTo(expectedOutput.toString()));
  }

  @Test
  public void genruleDirectoryOutputIsCleanedBeforeBuildAndCacheFetch() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_directory_output_cleaned", temporaryFolder);
    workspace.setUp();
    workspace.enableDirCache();

    String mkDirAnotherTargetName = targetWithSuffix("//:mkdir_another");
    workspace.copyFile("BUCK.1", "BUCK");
    workspace.runBuckCommand("build", mkDirAnotherTargetName).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(mkDirAnotherTargetName);
    assertTrue(
        String.format("%s should be built", mkDirAnotherTargetName),
        Files.isRegularFile(
            getOutputPath(workspace, mkDirAnotherTargetName, "another_directory/file")));

    String mkDirTargetName = targetWithSuffix("//:mkdir");
    workspace.runBuckCommand("build", mkDirTargetName).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(mkDirTargetName);
    assertTrue(
        "BUCK.1 should create its output",
        Files.isRegularFile(getOutputPath(workspace, mkDirTargetName, "directory/one")));
    assertFalse(
        "BUCK.1 should not touch the output of BUCK.2",
        Files.isRegularFile(getOutputPath(workspace, mkDirTargetName, "directory/two")));
    assertTrue(
        "output of mkdir_another should still exist",
        Files.isRegularFile(
            getOutputPath(workspace, mkDirAnotherTargetName, "another_directory/file")));

    workspace.copyFile("BUCK.2", "BUCK");
    workspace.runBuckCommand("build", mkDirTargetName).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(mkDirTargetName);
    assertFalse(
        "Output of BUCK.1 should be deleted before output of BUCK.2 is built",
        Files.isRegularFile(getOutputPath(workspace, mkDirTargetName, "directory/one")));
    assertTrue(
        "BUCK.2 should create its output",
        Files.isRegularFile(getOutputPath(workspace, mkDirTargetName, "directory/two")));
    assertTrue(
        "output of mkdir_another should still exist",
        Files.isRegularFile(
            getOutputPath(workspace, mkDirAnotherTargetName, "another_directory/file")));

    workspace.copyFile("BUCK.1", "BUCK");
    workspace.runBuckCommand("build", mkDirTargetName).assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache(mkDirTargetName);
    assertTrue(
        "Output of BUCK.1 should be fetched from the cache",
        Files.isRegularFile(
            getOutputPath(workspace, mkDirAnotherTargetName, "another_directory/file")));
    assertFalse(
        "Output of BUCK.2 should be deleted before output of BUCK.1 is fetched from cache",
        Files.isRegularFile(getOutputPath(workspace, mkDirTargetName, "directory/two")));
    assertTrue(
        "output of mkdir_another should still exist",
        Files.isRegularFile(
            getOutputPath(workspace, mkDirAnotherTargetName, "another_directory/file")));
    assertTrue(Files.isDirectory(getOutputPath(workspace, mkDirTargetName, "directory")));
  }

  @Test
  public void genruleCleansEntireOutputDirectory() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_robust_cleaning", temporaryFolder);
    workspace.setUp();

    String targetName = targetWithSuffix("//:write");
    workspace.copyFile("BUCK.1", "BUCK");
    workspace.runBuckCommand("build", targetName).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(targetName);
    assertTrue(
        String.format("%s should be built", targetName),
        Files.isRegularFile(getOutputPath(workspace, targetName, "one")));

    workspace.copyFile("BUCK.2", "BUCK");
    workspace.runBuckCommand("build", targetName).assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally(targetName);
    assertFalse(
        "Output of BUCK.1 should be deleted before output of BUCK.2 is built",
        Files.isRegularFile(getOutputPath(workspace, targetName, "one")));
    assertTrue(
        "BUCK.2 should create its output",
        Files.isRegularFile(getOutputPath(workspace, targetName, "two")));
  }

  @Test
  public void genruleDirectorySourcePath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_directory_source_path", temporaryFolder);
    workspace.setUp();

    String targetName = targetWithSuffix("//:cpdir");
    workspace.runBuckBuild(targetName).assertSuccess();

    assertTrue(Files.isDirectory(getOutputPath(workspace, targetName, "copy")));
    assertTrue(Files.isRegularFile(getOutputPath(workspace, targetName, "copy/hello")));
  }

  @Test
  public void twoGenrulesWithTheSameOutputFileShouldNotOverwriteOneAnother() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_overwrite", temporaryFolder);
    workspace.setUp();

    String targetNameOne = targetWithLabelIfNonEmptySuffix("//:genrule-one", "output");
    String targetNameTwo = targetWithLabelIfNonEmptySuffix("//:genrule-two", "output");
    // The two genrules run in this test have the same inputs and same output name
    Path output = workspace.buildAndReturnOutput(targetNameOne);
    String originalOutput = new String(Files.readAllBytes(output), UTF_8);

    output = workspace.buildAndReturnOutput(targetNameTwo);
    String updatedOutput = new String(Files.readAllBytes(output), UTF_8);

    assertNotEquals(originalOutput, updatedOutput);

    // Finally, reinvoke the first rule.
    output = workspace.buildAndReturnOutput(targetNameOne);
    String originalOutput2 = new String(Files.readAllBytes(output), UTF_8);

    assertEquals(originalOutput, originalOutput2);
  }

  @Test
  public void executableGenruleForSingleOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_executable", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("run", "//:binary");
    buildResult.assertSuccess();
  }

  @Test
  public void executableGenruleWithMultipleOutputs() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_executable", temporaryFolder);
    workspace.setUp();

    // Multiple outputs doesn't support buck run yet, so get the output directly and execute it
    Path executable = workspace.buildAndReturnOutput("//:binary_outs[output]");
    DefaultProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());
    ProcessExecutor.Result processResult =
        processExecutor.launchAndExecute(
            ProcessExecutorParams.builder().addCommand(executable.toString()).build());
    assertEquals(0, processResult.getExitCode());
  }

  @Test
  public void genruleExeMacro() throws Exception {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_exe_macro", temporaryFolder);
    workspace.setUp();

    Path result =
        workspace.buildAndReturnOutput(targetWithLabelIfNonEmptySuffix("//:exe_macro", "output"));
    assertTrue(result.endsWith("example_out.txt"));
    assertEquals("hello\n", workspace.getFileContents(result));
  }

  @Test
  public void exeMacroThrowsForNamedOutputsIfNotOneOutput() throws Exception {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_exe_macro", temporaryFolder);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckBuild("//:exe_macro_with_invalid_outputs").assertFailure();
    assertTrue(
        result
            .getStderr()
            .contains("Unexpectedly found 0 outputs for //:extra_layer_for_test[DEFAULT]"));
  }

  @Test
  public void genruleZipOutputsAreScrubbed() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_zip_scrubber", temporaryFolder);
    workspace.setUp();

    Path outputOne =
        workspace.buildAndReturnOutput(targetWithLabelIfNonEmptySuffix("//:genrule-one", "output"));
    Path outputTwo =
        workspace.buildAndReturnOutput(targetWithLabelIfNonEmptySuffix("//:genrule-two", "output"));

    assertZipsAreEqual(outputOne, outputTwo);
  }

  @Test
  public void genruleZipOutputsExtendedTimestampsAreScrubbed() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_zip_scrubber", temporaryFolder);
    workspace.setUp();

    Path outputOne =
        workspace.buildAndReturnOutput(
            targetWithLabelIfNonEmptySuffix("//:extended-time-one", "output"));
    Path outputTwo =
        workspace.buildAndReturnOutput(
            targetWithLabelIfNonEmptySuffix("//:extended-time-two", "output"));

    assertZipsAreEqual(outputOne, outputTwo);
  }

  @Test
  public void genruleCanUseExportFileInReferenceMode() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_export_file", temporaryFolder);
    workspace.setUp();

    String targetName = targetWithSuffix("//:re-exported");
    workspace.runBuckBuild(targetName).assertSuccess();

    // rebuild with changed contents
    String contents = "new contents";
    workspace.writeContentsToPath(contents, "source.txt");
    Path genruleOutput = workspace.buildAndReturnOutput(targetName);

    assertEquals(contents, workspace.getFileContents(genruleOutput));
  }

  @Test
  public void genruleWithSandboxFailsAccessingUndeclaredFile() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_with_sandbox", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(targetWithLabelIfNonEmptySuffix("//:cat_input", "output"));
    String expected =
        new String(Files.readAllBytes(workspace.getPath("undeclared_input.txt")), UTF_8);
    String actual = new String(Files.readAllBytes(output), UTF_8);
    assertEquals(expected, actual);

    ProcessResult result = workspace.runBuckBuild(targetWithSuffix("//:cat_input_with_sandbox"));
    assertTrue(result.getStderr().contains("undeclared_input.txt: Operation not permitted"));
  }

  @Test
  public void disabledDarwinSandboxingNotBreakingViolators() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_with_sandbox", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "--config",
            "sandbox.darwin_sandbox_enabled=False",
            targetWithLabelIfNonEmptySuffix("//:cat_input", "output"));
    String actual = new String(Files.readAllBytes(output), UTF_8);

    String expected =
        new String(Files.readAllBytes(workspace.getPath("undeclared_input.txt")), UTF_8);
    assertEquals(expected, actual);
  }

  @Test
  public void disabledGenruleSandboxingNotBreakingViolators() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_with_sandbox", temporaryFolder);
    workspace.setUp();

    Path output =
        workspace.buildAndReturnOutput(
            "--config",
            "sandbox.genrule_sandbox_enabled=False",
            targetWithLabelIfNonEmptySuffix("//:cat_input", "output"));
    String actual = new String(Files.readAllBytes(output), UTF_8);

    String expected =
        new String(Files.readAllBytes(workspace.getPath("undeclared_input.txt")), UTF_8);
    assertEquals(expected, actual);
  }

  @Test
  public void testGenruleWithMacrosRuleKeyDoesNotDependOnAbsolutePath() throws IOException {
    Sha1HashCode rulekey1 =
        buildAndGetRuleKey(
            "genrule_rulekey", temporaryFolder.newFolder(), targetWithSuffix("//:bar"));
    Sha1HashCode rulekey2 =
        buildAndGetRuleKey(
            "genrule_rulekey", temporaryFolder.newFolder(), targetWithSuffix("//:bar"));

    assertEquals(rulekey1, rulekey2);
  }

  /**
   * Tests that we handle genrules whose $OUT is "." and wants to make $OUT a directory without
   * crashing. This test is not applicable to outs because $OUT would already be a directory.
   */
  @Test
  public void testGenruleWithDotOutWorks() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "genrule_dot_out", temporaryFolder);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:mkdir");
    assertTrue(Files.exists(output.resolve("hello")));
  }

  @Test
  public void testGenruleWithChangingNestedDirectory() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_path_changes", temporaryFolder);
    workspace.setUp();

    workspace.runBuckBuild(targetWithSuffix("//:hello")).assertSuccess();
    workspace.runBuckBuild(targetWithSuffix("//:hello_break")).assertSuccess();
  }

  @Test
  public void srcsMap() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "genrule_srcs_map", temporaryFolder);
    workspace.setUp();
    workspace.runBuckBuild(targetWithSuffix("//:gen")).assertSuccess();
  }

  @Test
  public void namedOutputsMapToCorrespondingOutputs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_outputs_map", temporaryFolder);
    workspace.setUp();

    Path result = workspace.buildAndReturnOutput("//:outputs_map[output1]");
    assertTrue(result.endsWith("out1.txt"));
    assertEquals("something" + System.lineSeparator(), workspace.getFileContents(result));

    result = workspace.buildAndReturnOutput("//:outputs_map[output2]");
    assertTrue(result.endsWith("out2.txt"));
    assertEquals("another" + System.lineSeparator(), workspace.getFileContents(result));
  }

  @Test
  public void namedOutputCanBeInMultipleGroups() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_named_output_groups", temporaryFolder);
    workspace.setUp();

    Path result = workspace.buildAndReturnOutput("//:named_output_groups[output1]");
    assertTrue(result.endsWith("out.txt"));
    assertEquals("something" + System.lineSeparator(), workspace.getFileContents(result));

    result = workspace.buildAndReturnOutput("//:named_output_groups[output2]");
    assertTrue(result.endsWith("out.txt"));
    assertEquals("something" + System.lineSeparator(), workspace.getFileContents(result));
  }

  @Test
  public void canUseGenruleOutputLabelInSrcs() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_output_label_used_in_srcs", temporaryFolder);
    workspace.setUp();

    Path result = workspace.buildAndReturnOutput("//:file_with_named_outputs");
    assertEquals("something" + System.lineSeparator(), workspace.getFileContents(result));
  }

  @Test
  public void cannotHaveBothOutAndOuts() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_incompatible_attrs", temporaryFolder);
    workspace.setUp();
    ProcessResult processResult = workspace.runBuckBuild("//:binary");
    processResult.assertExitCode(ExitCode.BUILD_ERROR);
    assertTrue(
        processResult
            .getStderr()
            .contains("One and only one of 'out' or 'outs' must be present in genrule."));
  }

  @Test
  public void writingInWorkingDirWritesInSrcsDir() throws IOException {
    String targetName = targetWithSuffix("//:working_dir");
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_working_dir", temporaryFolder);
    workspace.setUp();
    workspace.runBuckBuild(targetName).assertSuccess();
    assertTrue(
        Files.exists(
            workspace
                .getGenPath(BuildTargetFactory.newInstance(targetName), "%s__srcs")
                .resolve("hello.txt")));
  }

  @Test
  public void genruleWithInvalidOutParameterFails() throws IOException {

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_invalid_out", temporaryFolder);
    workspace.setUp();

    String[] targets = {
      "//:genrule",
      "//:genrule-double-dot",
      "//:genrule-double-dot-middle",
      "//:genrule-double-slash",
      "//:genrule-middle-dot",
      "//:genrule-slash-end",
    };

    for (String target : targets) {
      String targetName = targetWithSuffix(target);
      ProcessResult processResult = workspace.runBuckCommand("build", targetName);
      processResult.assertFailure();
      assertThat(
          processResult.getStderr(),
          containsString(
              "The output path must be relative, simple, non-empty and not cross package boundary"));
    }
  }

  private Sha1HashCode buildAndGetRuleKey(String scenario, Path temporaryFolder, String target)
      throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenario, temporaryFolder);
    workspace.setUp();
    workspace.runBuckBuild(target);
    return workspace.getBuildLog().getRuleKey(target);
  }

  private void assertZipsAreEqual(Path zipPathOne, Path zipPathTwo) throws IOException {
    try (ZipFile zipOne = new ZipFile(zipPathOne.toFile());
        ZipFile zipTwo = new ZipFile(zipPathTwo.toFile())) {
      Enumeration<? extends ZipEntry> entriesOne = zipOne.entries(), entriesTwo = zipTwo.entries();

      while (entriesOne.hasMoreElements()) {
        assertTrue(entriesTwo.hasMoreElements());
        ZipEntry entryOne = entriesOne.nextElement(), entryTwo = entriesTwo.nextElement();
        assertEquals(zipEntryDebugString(entryOne), zipEntryDebugString(entryTwo));
        assertEquals(zipEntryData(zipOne, entryOne), zipEntryData(zipTwo, entryTwo));
      }
      assertFalse(entriesTwo.hasMoreElements());
    }
    assertEquals(
        new String(Files.readAllBytes(zipPathOne)), new String(Files.readAllBytes(zipPathTwo)));
  }

  private String zipEntryDebugString(ZipEntry entryOne) {
    return "<ZE name="
        + entryOne.getName()
        + " crc="
        + entryOne.getCrc()
        + " comment="
        + entryOne.getComment()
        + " size="
        + entryOne.getSize()
        + " atime="
        + entryOne.getLastAccessTime()
        + " mtime="
        + entryOne.getLastModifiedTime()
        + " ctime="
        + entryOne.getCreationTime();
  }

  private String zipEntryData(ZipFile zip, ZipEntry entry) throws IOException {
    return CharStreams.toString(new InputStreamReader(zip.getInputStream(entry)));
  }

  private String targetWithSuffix(String targetName) {
    return targetName + targetSuffix;
  }

  private String targetWithLabelIfNonEmptySuffix(String targetName, String label) {
    return targetSuffix.isEmpty()
        ? targetName
        : String.format("%s[%s]", targetWithSuffix(targetName), label);
  }

  private Path getOutputPath(ProjectWorkspace workspace, String targetName, String outputName)
      throws IOException {
    String format = targetSuffix.isEmpty() ? "" : "__";
    return workspace
        .getGenPath(BuildTargetFactory.newInstance(targetName), "%s" + format)
        .resolve(outputName);
  }
}
