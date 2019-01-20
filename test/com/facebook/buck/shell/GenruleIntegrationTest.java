/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;

public class GenruleIntegrationTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

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
    assumeTrue(
        "This genrule uses the 'bash' argument, which is not supported on Windows. ",
        Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_failing_command", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//:fail", "--verbose", "10");
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
    String outputPattern =
        "(?s).*Command failed with exit code 1\\.(?s).*"
            + "When running <\\(cd buck-out/gen/fail__srcs && "
            + "/bin/bash -e .*/buck-out/tmp/genrule-[0-9]*\\.sh\\)>(?s).*";

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

    ProcessResult processResult = workspace.runBuckCommand("build", "//:genrule");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "The 'out' parameter of genrule //:genrule is '', which is not a valid file name."));
  }

  @Test
  public void genruleWithAbsoluteOutParameterFails() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_absolute_out", temporaryFolder);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckCommand("build", "//:genrule");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(
            "The 'out' parameter of genrule //:genrule is '/tmp/file', "
                + "which is not a valid file name."));
  }

  @Test
  public void genruleDirectoryOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_directory_output", temporaryFolder);
    workspace.setUp();
    workspace.enableDirCache();

    workspace.runBuckCommand("build", "//:mkdir").assertSuccess();

    assertThat(Files.isDirectory(workspace.resolve("buck-out/gen/mkdir/directory")), equalTo(true));
    assertThat(
        workspace.getFileContents("buck-out/gen/mkdir/directory/file"),
        equalTo("something" + System.lineSeparator()));

    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();

    assertThat(Files.isDirectory(workspace.resolve("buck-out/gen")), equalTo(false));
    // Retrieving the genrule output from the local cache should recreate the directory contents.
    workspace.runBuckCommand("build", "//:mkdir").assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache("//:mkdir");
    assertThat(Files.isDirectory(workspace.resolve("buck-out/gen/mkdir/directory")), equalTo(true));
    assertThat(
        workspace.getFileContents("buck-out/gen/mkdir/directory/file"),
        equalTo("something" + System.lineSeparator()));
  }

  @Test
  public void genruleWithBigCommand() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_big_command", temporaryFolder);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:big").assertSuccess();

    Path outputPath = workspace.resolve("buck-out/gen/big/file");
    assertThat(Files.isRegularFile(outputPath), equalTo(true));

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

    workspace.copyFile("BUCK.1", "BUCK");
    workspace.runBuckCommand("build", "//:mkdir_another").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:mkdir_another");
    assertTrue(
        "mkdir_another should be built",
        Files.isRegularFile(
            workspace.resolve("buck-out/gen/mkdir_another/another_directory/file")));

    workspace.runBuckCommand("build", "//:mkdir").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:mkdir");
    assertTrue(
        "BUCK.1 should create its output",
        Files.isRegularFile(workspace.resolve("buck-out/gen/mkdir/directory/one")));
    assertFalse(
        "BUCK.1 should not touch the output of BUCK.2",
        Files.isRegularFile(workspace.resolve("buck-out/gen/mkdir/directory/two")));
    assertTrue(
        "output of mkdir_another should still exist",
        Files.isRegularFile(
            workspace.resolve("buck-out/gen/mkdir_another/another_directory/file")));

    workspace.copyFile("BUCK.2", "BUCK");
    workspace.runBuckCommand("build", "//:mkdir").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:mkdir");
    assertFalse(
        "Output of BUCK.1 should be deleted before output of BUCK.2 is built",
        Files.isRegularFile(workspace.resolve("buck-out/gen/mkdir/directory/one")));
    assertTrue(
        "BUCK.2 should create its output",
        Files.isRegularFile(workspace.resolve("buck-out/gen/mkdir/directory/two")));
    assertTrue(
        "output of mkdir_another should still exist",
        Files.isRegularFile(
            workspace.resolve("buck-out/gen/mkdir_another/another_directory/file")));

    workspace.copyFile("BUCK.1", "BUCK");
    workspace.runBuckCommand("build", "//:mkdir").assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache("//:mkdir");
    assertTrue(
        "Output of BUCK.1 should be fetched from the cache",
        Files.isRegularFile(workspace.resolve("buck-out/gen/mkdir/directory/one")));
    assertFalse(
        "Output of BUCK.2 should be deleted before output of BUCK.1 is fetched from cache",
        Files.isRegularFile(workspace.resolve("buck-out/gen/mkdir/directory/two")));
    assertTrue(
        "output of mkdir_another should still exist",
        Files.isRegularFile(
            workspace.resolve("buck-out/gen/mkdir_another/another_directory/file")));
    assertThat(Files.isDirectory(workspace.resolve("buck-out/gen/mkdir/directory")), equalTo(true));
  }

  @Test
  public void genruleCleansEntireOutputDirectory() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_robust_cleaning", temporaryFolder);
    workspace.setUp();

    workspace.copyFile("BUCK.1", "BUCK");
    workspace.runBuckCommand("build", "//:write").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:write");
    assertTrue(
        "write should be built", Files.isRegularFile(workspace.resolve("buck-out/gen/write/one")));

    workspace.copyFile("BUCK.2", "BUCK");
    workspace.runBuckCommand("build", "//:write").assertSuccess();

    workspace.getBuildLog().assertTargetBuiltLocally("//:write");
    assertFalse(
        "Output of BUCK.1 should be deleted before output of BUCK.2 is built",
        Files.isRegularFile(workspace.resolve("buck-out/gen/write/one")));
    assertTrue(
        "BUCK.2 should create its output",
        Files.isRegularFile(workspace.resolve("buck-out/gen/write/two")));
  }

  @Test
  public void genruleDirectorySourcePath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_directory_source_path", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//:cpdir");
    buildResult.assertSuccess();

    assertThat(Files.isDirectory(workspace.resolve("buck-out/gen/cpdir/copy")), equalTo(true));
    assertThat(
        Files.isRegularFile(workspace.resolve("buck-out/gen/cpdir/copy/hello")), equalTo(true));
  }

  @Test
  public void genruleNoRemoteParsedAndDoesNotImpactBuild() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_directory_source_path", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//:cpdir_no_remote");
    buildResult.assertSuccess();

    assertThat(
        Files.isDirectory(workspace.resolve("buck-out/gen/cpdir_no_remote/copy")), equalTo(true));
    assertThat(
        Files.isRegularFile(workspace.resolve("buck-out/gen/cpdir_no_remote/copy/hello")),
        equalTo(true));
  }

  @Test
  public void twoGenrulesWithTheSameOutputFileShouldNotOverwriteOneAnother() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_overwrite", temporaryFolder);
    workspace.setUp();

    // The two genrules run in this test have the same inputs and same output name
    Path output = workspace.buildAndReturnOutput("//:genrule-one");
    String originalOutput = new String(Files.readAllBytes(output), UTF_8);

    output = workspace.buildAndReturnOutput("//:genrule-two");
    String updatedOutput = new String(Files.readAllBytes(output), UTF_8);

    assertNotEquals(originalOutput, updatedOutput);

    // Finally, reinvoke the first rule.
    output = workspace.buildAndReturnOutput("//:genrule-one");
    String originalOutput2 = new String(Files.readAllBytes(output), UTF_8);

    assertEquals(originalOutput, originalOutput2);
  }

  @Test
  public void executableGenrule() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_executable", temporaryFolder);
    workspace.setUp();

    ProcessResult buildResult = workspace.runBuckCommand("run", "//:binary");
    buildResult.assertSuccess();
  }

  @Test
  public void genruleZipOutputsAreScrubbed() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_zip_scrubber", temporaryFolder);
    workspace.setUp();

    Path outputOne = workspace.buildAndReturnOutput("//:genrule-one");
    Path outputTwo = workspace.buildAndReturnOutput("//:genrule-two");

    assertZipsAreEqual(outputOne, outputTwo);
  }

  @Test
  public void genruleZipOutputsExtendedTimestampsAreScrubbed() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_zip_scrubber", temporaryFolder);
    workspace.setUp();

    Path outputOne = workspace.buildAndReturnOutput("//:extended-time-one");
    Path outputTwo = workspace.buildAndReturnOutput("//:extended-time-two");

    assertZipsAreEqual(outputOne, outputTwo);
  }

  @Test
  public void genruleCanUseExportFileInReferenceMode() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_export_file", temporaryFolder);
    workspace.setUp();

    workspace.runBuckBuild("//:re-exported").assertSuccess();

    // rebuild with changed contents
    String contents = "new contents";
    workspace.writeContentsToPath(contents, "source.txt");
    Path genruleOutput = workspace.buildAndReturnOutput("//:re-exported");

    assertEquals(contents, workspace.getFileContents(genruleOutput));
  }

  @Test
  public void genruleWithSandboxFailsAccessingUndeclaredFile() throws IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "genrule_with_sandbox", temporaryFolder);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:cat_input");
    String expected =
        new String(Files.readAllBytes(workspace.getPath("undeclared_input.txt")), UTF_8);
    String actual = new String(Files.readAllBytes(output), UTF_8);
    assertEquals(expected, actual);

    ProcessResult result = workspace.runBuckBuild("//:cat_input_with_sandbox");
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
            "--config", "sandbox.darwin_sandbox_enabled=False", "//:cat_input");
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
            "--config", "sandbox.genrule_sandbox_enabled=False", "//:cat_input");
    String actual = new String(Files.readAllBytes(output), UTF_8);

    String expected =
        new String(Files.readAllBytes(workspace.getPath("undeclared_input.txt")), UTF_8);
    assertEquals(expected, actual);
  }

  @Test
  public void testGenruleWithMacrosRuleKeyDoesNotDependOnAbsolutePath() throws IOException {
    Sha1HashCode rulekey1 =
        buildAndGetRuleKey("genrule_rulekey", temporaryFolder.newFolder(), "//:bar");
    Sha1HashCode rulekey2 =
        buildAndGetRuleKey("genrule_rulekey", temporaryFolder.newFolder(), "//:bar");

    assertEquals(rulekey1, rulekey2);
  }

  @Test
  public void srcsMap() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "genrule_srcs_map", temporaryFolder);
    workspace.setUp();
    workspace.runBuckBuild("//:gen").assertSuccess();
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
}
