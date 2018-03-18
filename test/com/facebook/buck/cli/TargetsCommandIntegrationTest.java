/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static com.facebook.buck.util.MoreStringsForTests.normalizeNewlines;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ThriftRuleKeyDeserializer;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.thrift.TException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TargetsCommandIntegrationTest {
  private static final String ABSOLUTE_PATH_TO_FILE_OUTSIDE_THE_PROJECT_THAT_EXISTS_ON_THE_FS =
      "/bin/sh";

  private static final CharMatcher LOWER_CASE_HEX_DIGITS =
      CharMatcher.inRange('0', '9').or(CharMatcher.inRange('a', 'f'));

  private static final String INCOMPATIBLE_OPTIONS_MSG1 =
      "option \"--show-target-hash\" cannot be used with the option(s) [--show-rulekey]";
  private static final String INCOMPATIBLE_OPTIONS_MSG2 =
      "option \"--show-rulekey (--show_rulekey)\" cannot be used with the option(s) [--show-target-hash]";

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testOutputPath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-output", "//:test", "//:another-test");
    result.assertSuccess();
    assertEquals(
        "//:another-test "
            + MorePaths.pathWithPlatformSeparators("buck-out/gen/another-test/test-output")
            + "\n"
            + "//:test "
            + MorePaths.pathWithPlatformSeparators("buck-out/gen/test/test-output")
            + "\n",
        result.getStdout());
  }

  @Test
  public void testRuleKeyWithOneTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-rulekey", "//:test");
    result.assertSuccess();
    assertThat(result.getStdout().trim(), Matchers.matchesPattern("//:test [a-f0-9]{40}"));
  }

  @Test
  public void testRuleKey() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-rulekey", "//:test", "//:another-test");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(result.getStdout(), "//:another-test", "//:test");
  }

  @Test
  public void testBothOutputAndRuleKey() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-rulekey", "--show-output", "//:test");
    result.assertSuccess();
    assertThat(
        result.getStdout().trim(),
        Matchers.matchesPattern(
            "//:test [a-f0-9]{40} "
                + Pattern.quote(
                    MorePaths.pathWithPlatformSeparators("buck-out/gen/test/test-output"))));
  }

  @Test
  public void testOutputWithoutTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-output");
    result.assertSuccess();
    assertEquals(
        "//:another-test "
            + MorePaths.pathWithPlatformSeparators("buck-out/gen/another-test/test-output")
            + "\n"
            + "//:java_lib "
            + MorePaths.pathWithPlatformSeparators(
                "buck-out/gen/lib__java_lib__output/java_lib.jar")
            + " "
            + MorePaths.pathWithPlatformSeparators("buck-out/annotation/__java_lib_gen__")
            + "\n"
            + "//:plugin"
            + "\n"
            + "//:test "
            + MorePaths.pathWithPlatformSeparators("buck-out/gen/test/test-output")
            + "\n",
        result.getStdout());
  }

  @Test
  public void testRuleKeyWithoutTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-rulekey");
    result.assertSuccess();
    assertThat(
        result.getStdout().trim(),
        Matchers.matchesPattern(
            "//:another-test [a-f0-9]{40}\n//:java_lib [a-f0-9]{40}\n"
                + "//:plugin [a-f0-9]{40}\n//:test [a-f0-9]{40}"));
  }

  @Test
  public void testCellPath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-cell-path", "//:test");
    result.assertSuccess();
    assertEquals(
        "//:test " + MorePaths.pathWithPlatformSeparators(tmp.getRoot().toRealPath()) + "\n",
        result.getStdout());
  }

  @Test
  public void testCellPathAndOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-output", "--show-cell-path", "//:test");
    result.assertSuccess();
    assertEquals(
        "//:test "
            + MorePaths.pathWithPlatformSeparators(tmp.getRoot().toRealPath())
            + " "
            + MorePaths.pathWithPlatformSeparators("buck-out/gen/test/test-output")
            + "\n",
        result.getStdout());
  }

  private ImmutableList<String> parseAndVerifyTargetsAndHashes(
      String outputLine, String... targets) {
    List<String> lines =
        Splitter.on('\n').splitToList(CharMatcher.whitespace().trimFrom(outputLine));
    assertEquals(targets.length, lines.size());
    ImmutableList.Builder<String> hashes = ImmutableList.builder();
    for (int i = 0; i < targets.length; ++i) {
      String line = lines.get(i);
      String target = targets[i];
      hashes.add(parseAndVerifyTargetAndHash(line, target));
    }
    return hashes.build();
  }

  private String parseAndVerifyTargetAndHash(String outputLine, String target) {
    List<String> targetAndHash =
        Splitter.on(' ').splitToList(CharMatcher.whitespace().trimFrom(outputLine));
    assertEquals(2, targetAndHash.size());
    assertEquals(target, targetAndHash.get(0));
    assertFalse(targetAndHash.get(1).isEmpty());
    assertTrue(LOWER_CASE_HEX_DIGITS.matchesAllOf(targetAndHash.get(1)));
    return targetAndHash.get(1);
  }

  @Test
  public void testTargetHashWithoutTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-target-hash");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(
        result.getStdout(), "//:another-test", "//:java_lib", "//:plugin", "//:test");
  }

  @Test
  public void testRuleKeyWithReferencedFiles() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_library_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-rulekey", "--referenced-file", "Test.java");
    result.assertSuccess();
    parseAndVerifyTargetAndHash(result.getStdout(), "//:test");
  }

  @Test
  public void testRuleKeyWithReferencedFilesAndDetectTestChanges() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_library_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--show-rulekey", "--detect-test-changes", "--referenced-file", "Test.java");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(result.getStdout(), "//:lib", "//:test");
  }

  @Test
  public void testTargetHash() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-target-hash", "//:test", "//:another-test");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(result.getStdout(), "//:another-test", "//:test");
  }

  @Test
  public void testTargetHashAndRuleKeyIncompatibility() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-target-hash", "--show-rulekey", "//:test");
    result.assertSpecialExitCode(
        "--show-target-hash and --show-rulekey should be incompatible", ExitCode.COMMANDLINE_ERROR);
    String stderr = result.getStderr();
    assertTrue(
        stderr,
        stderr.contains(INCOMPATIBLE_OPTIONS_MSG1) || stderr.contains(INCOMPATIBLE_OPTIONS_MSG2));
  }

  @Test
  public void testTargetHashAndRuleKeyAndOutputIncompatibility() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--show-target-hash", "--show-rulekey", "--show-output", "//:test");

    result.assertSpecialExitCode(
        "--show-target-hash and --show-rulekey and --show-output should be incompatible",
        ExitCode.COMMANDLINE_ERROR);
    String stderr = result.getStderr();
    assertTrue(
        stderr,
        stderr.contains(INCOMPATIBLE_OPTIONS_MSG1) || stderr.contains(INCOMPATIBLE_OPTIONS_MSG2));
  }

  @Test
  public void testTargetHashXcodeWorkspaceWithTests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--show-target-hash", "--detect-test-changes", "//workspace:workspace");
    result.assertSuccess();
    parseAndVerifyTargetAndHash(result.getStdout(), "//workspace:workspace");
  }

  @Test
  public void testTargetHashXcodeWorkspaceWithTestsForAllTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-target-hash", "--detect-test-changes");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(
        result.getStdout(),
        "//bin:bin",
        "//bin:genrule",
        "//lib:lib",
        "//test:test",
        "//workspace:workspace");
  }

  @Test
  public void testTargetHashWithBrokenTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "detect_test_changes_with_broken_targets", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--show-target-hash", "--detect-test-changes", "//:test");
    result.assertSuccess();
    parseAndVerifyTargetAndHash(result.getStdout(), "//:test");
  }

  @Test
  public void testTargetHashXcodeWorkspaceWithoutTestsDiffersFromWithTests() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--show-target-hash", "--detect-test-changes", "//workspace:workspace");
    result.assertSuccess();
    String hash = parseAndVerifyTargetAndHash(result.getStdout(), "//workspace:workspace");

    ProcessResult result2 =
        workspace.runBuckCommand("targets", "--show-target-hash", "//workspace:workspace");
    result2.assertSuccess();
    String hash2 = parseAndVerifyTargetAndHash(result2.getStdout(), "//workspace:workspace");
    assertNotEquals(hash, hash2);
  }

  @Test
  public void testTargetHashChangesAfterModifyingSourceFile() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--show-target-hash", "--detect-test-changes", "//workspace:workspace");
    result.assertSuccess();
    String hash = parseAndVerifyTargetAndHash(result.getStdout(), "//workspace:workspace");

    String fileName = "test/Test.m";
    Files.write(workspace.getPath(fileName), "// This is not a test\n".getBytes(UTF_8));
    ProcessResult result2 =
        workspace.runBuckCommand(
            "targets", "--show-target-hash", "--detect-test-changes", "//workspace:workspace");
    result2.assertSuccess();
    String hash2 = parseAndVerifyTargetAndHash(result2.getStdout(), "//workspace:workspace");

    assertNotEquals(hash, hash2);
  }

  @Test
  public void testTargetHashChangesAfterModifyingSourceFileForAllTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-target-hash", "--detect-test-changes");
    result.assertSuccess();
    List<String> hashes =
        parseAndVerifyTargetsAndHashes(
            result.getStdout(),
            "//bin:bin",
            "//bin:genrule",
            "//lib:lib",
            "//test:test",
            "//workspace:workspace");

    String fileName = "test/Test.m";
    Files.write(workspace.getPath(fileName), "// This is not a test\n".getBytes(UTF_8));
    ProcessResult result2 =
        workspace.runBuckCommand("targets", "--show-target-hash", "--detect-test-changes");
    result2.assertSuccess();
    List<String> hashesAfterModification =
        parseAndVerifyTargetsAndHashes(
            result2.getStdout(),
            "//bin:bin",
            "//bin:genrule",
            "//lib:lib",
            "//test:test",
            "//workspace:workspace");

    assertNotEquals(hashes.get(0), hashesAfterModification.get(0));
    // bin:genrule wasn't changed
    assertEquals(hashes.get(1), hashesAfterModification.get(1));
    assertNotEquals(hashes.get(2), hashesAfterModification.get(2));
    assertNotEquals(hashes.get(3), hashesAfterModification.get(3));
    assertNotEquals(hashes.get(4), hashesAfterModification.get(4));
  }

  @Test
  public void testTargetHashChangesAfterDeletingSourceFile() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--show-target-hash", "--detect-test-changes", "//workspace:workspace");
    result.assertSuccess();
    String hash = parseAndVerifyTargetAndHash(result.getStdout(), "//workspace:workspace");

    String fileName = "test/Test.m";
    Files.delete(workspace.getPath(fileName));
    ProcessResult result2 =
        workspace.runBuckCommand(
            "targets", "--show-target-hash", "--detect-test-changes", "//workspace:workspace");
    result2.assertSuccess();

    String hash2 = parseAndVerifyTargetAndHash(result2.getStdout(), "//workspace:workspace");
    assertNotEquals(hash, hash2);
  }

  @Test
  public void testBuckTargetsReferencedFileWithFileOutsideOfProject() throws IOException {
    // The contents of the project are not relevant for this test. We just want a non-empty project
    // to prevent against a regression where all of the build rules are printed.
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "project_slice", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets",
            "--referenced-file",
            ABSOLUTE_PATH_TO_FILE_OUTSIDE_THE_PROJECT_THAT_EXISTS_ON_THE_FS);
    result.assertSuccess(
        "Even though the file is outside the project, " + "`buck targets` should succeed.");
    assertEquals("Because no targets match, stdout should be empty.", "", result.getStdout());
  }

  @Test
  public void testBuckTargetsReferencedFileWithFilesInAndOutsideOfProject() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "project_slice", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets",
            "--type",
            "prebuilt_jar",
            "--referenced-file",
            ABSOLUTE_PATH_TO_FILE_OUTSIDE_THE_PROJECT_THAT_EXISTS_ON_THE_FS,
            "libs/guava.jar", // relative path in project
            tmp.getRoot().resolve("libs/junit.jar").toString()); // absolute path in project
    result.assertSuccess(
        "Even though one referenced file is outside the project, "
            + "`buck targets` should succeed.");
    assertEquals(
        ImmutableSet.of("//libs:guava", "//libs:junit"),
        ImmutableSet.copyOf(Splitter.on('\n').omitEmptyStrings().split(result.getStdout())));
  }

  @Test
  public void testBuckTargetsReferencedFileWithNonExistentFile() throws IOException {
    // The contents of the project are not relevant for this test. We just want a non-empty project
    // to prevent against a regression where all of the build rules are printed.
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "project_slice", tmp);
    workspace.setUp();

    String pathToNonExistentFile = "modules/dep1/dep2/hello.txt";
    assertFalse(Files.exists(workspace.getPath(pathToNonExistentFile)));
    ProcessResult result =
        workspace.runBuckCommand("targets", "--referenced-file", pathToNonExistentFile);
    result.assertSuccess("Even though the file does not exist, buck targets` should succeed.");
    assertEquals("Because no targets match, stdout should be empty.", "", result.getStdout());
  }

  @Test
  public void testValidateBuildTargetForNonAliasTarget() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "target_validation", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--resolve-alias", "//:test-library");
    assertTrue(result.getStdout(), result.getStdout().contains("//:test-library"));

    result = workspace.runBuckCommand("targets", "--resolve-alias", "cell//:test-library");
    assertTrue(result.getStdout(), result.getStdout().contains("//:test-library"));

    try {
      workspace.runBuckCommand("targets", "--resolve-alias", "//:");
    } catch (HumanReadableException e) {
      assertEquals("//: cannot end with a colon", e.getMessage());
    }

    try {
      workspace.runBuckCommand("targets", "--resolve-alias", "//:test-libarry");
    } catch (HumanReadableException e) {
      assertEquals("//:test-libarry is not a valid target.", e.getMessage());
    }

    try {
      workspace.runBuckCommand("targets", "--resolve-alias", "//blah/foo");
    } catch (HumanReadableException e) {
      assertEquals("//blah/foo must contain exactly one colon (found 0)", e.getMessage());
    }
  }

  @Test
  public void testJsonOutputWithShowOptions() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand("targets", "--json", "--show-output", "//:test");

    // Parse the observed JSON.
    JsonNode observed =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(result.getStdout()));

    System.out.println(observed);

    String expectedJson = workspace.getFileContents("output_path_json.js");
    JsonNode expected =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(normalizeNewlines(expectedJson)));

    MatcherAssert.assertThat(
        "Output from targets command should match expected JSON.", observed, equalTo(expected));
  }

  @Test
  public void testJsonOutputWithShowCellPath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand("targets", "--json", "--show-cell-path", "//:test");

    // Parse the observed JSON.
    JsonNode observed =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(result.getStdout()));

    assertTrue(observed.isArray());
    JsonNode targetNode = observed.get(0);
    assertTrue(targetNode.isObject());
    JsonNode cellPath = targetNode.get("buck.cell_path");
    assertNotNull(cellPath);
    assertEquals(
        cellPath.asText(), MorePaths.pathWithPlatformSeparators(tmp.getRoot().toRealPath()));
  }

  @Test
  public void testJsonOutputWithShowFullOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();
    ProcessResult result =
        workspace.runBuckCommand("targets", "--json", "--show-full-output", "//:test");

    // Parse the observed JSON.
    JsonNode observed =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(result.getStdout()));
    assertTrue(observed.isArray());
    JsonNode targetNode = observed.get(0);
    assertTrue(targetNode.isObject());
    JsonNode cellPath = targetNode.get("buck.outputPath");
    assertNotNull(cellPath);

    Path expectedPath = tmp.getRoot().resolve("buck-out/gen/test/test-output");
    String expectedRootPath = MorePaths.pathWithPlatformSeparators(expectedPath);

    assertEquals(expectedRootPath, cellPath.asText());

    JsonNode ruleType = targetNode.get("buck.ruleType");
    assertNotNull(ruleType);
    assertEquals("genrule", ruleType.asText());
  }

  @Test
  public void testShowAllTargets() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets");
    result.assertSuccess();
    assertEquals(
        ImmutableSet.of(
            "//bin:bin", "//bin:genrule", "//lib:lib", "//test:test", "//workspace:workspace"),
        ImmutableSet.copyOf(Splitter.on('\n').omitEmptyStrings().split(result.getStdout())));
  }

  @Test
  public void testShowAllTargetsWithJson() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--json", "--show-output");
    result.assertSuccess();

    // Parse the observed JSON.
    JsonNode observed =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(result.getStdout()));

    String expectedJson = workspace.getFileContents("output_path_json_all.js");
    JsonNode expected =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(normalizeNewlines(expectedJson)));

    assertEquals("Output from targets command should match expected JSON.", expected, observed);
  }

  @Test
  public void testSpecificAttributesWithJson() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--json", "--show-output", "--output-attributes", "buck.outputPath", "name");
    result.assertSuccess();

    // Parse the observed JSON.
    JsonNode observed =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(result.getStdout()));

    String expectedJson = workspace.getFileContents("output_path_json_all_filtered.js");
    JsonNode expected =
        ObjectMappers.READER.readTree(ObjectMappers.createParser(normalizeNewlines(expectedJson)));

    assertEquals("Output from targets command should match expected JSON.", expected, observed);
  }

  @Test
  public void canSerializeSkylarkBuildFileToJson() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "skylark", tmp);
    workspace.setUp();

    workspace.addBuckConfigLocalOption("parser", "polyglot_parsing_enabled", "true");
    workspace.addBuckConfigLocalOption("parser", "default_build_file_syntax", "skylark");

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--json", "--show-output", "--output-attributes", "srcs");
    result.assertSuccess();

    assertThat(result.getStdout(), equalTo("[\n{\n  \"srcs\" : [ \"Foo.java\" ]\n}\n]\n"));
  }

  @Test
  public void testRuleKeyDotOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "targets_command_dot", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--show-rulekey", "--show-transitive-rulekeys", "--dot", "//:test1");
    result.assertSuccess();
    String output = result.getStdout().trim();

    Pattern pattern = Pattern.compile("digraph .* \\{(?<lines>.+)\\}", Pattern.DOTALL);
    Matcher matcher = pattern.matcher(output);

    assertTrue(matcher.find());
    List<String> lines =
        ImmutableList.copyOf(Splitter.on('\n').omitEmptyStrings().split(matcher.group("lines")));

    // make a vague assertion that test1 depends on test2; do not overload the test

    // node test1
    assertEquals(
        1, lines.stream().filter(p -> p.contains("test1") && !p.contains("test2")).count());

    // node test2
    assertEquals(
        1, lines.stream().filter(p -> p.contains("test2") && !p.contains("test1")).count());

    // edge test1 -> test2
    Pattern edgePattern = Pattern.compile("test1.+->.+test2");
    assertEquals(1, lines.stream().filter(p -> edgePattern.matcher(p).find()).count());
  }

  @Test
  public void writesBinaryRuleKeysToDisk() throws IOException, TException {
    Path logFile = tmp.newFile("out.bin");
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "just_build", tmp);
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
  public void targetsTransitiveRulekeys() throws Exception {
    assumeTrue(
        AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.IPHONESIMULATOR));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "targets_app_bundle_with_embedded_framework", tmp);
    workspace.setUp();

    workspace
        .runBuckCommand(
            "targets",
            "//:DemoApp#iphonesimulator-x86_64",
            "--show-rulekey",
            "--show-transitive-rulekeys")
        .assertSuccess();
  }

  @Test
  public void printsTransitiveTargetHashes() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "print_recursive_target_hashes", tmp);
    workspace.setUp();

    ProcessResult nontransitiveResult =
        workspace.runBuckCommand("targets", "--show-target-hash", "//foo:main", "//bar:main");
    ProcessResult transitiveResult =
        workspace.runBuckCommand(
            "targets",
            "--show-target-hash",
            "--show-transitive-target-hashes",
            "//foo:main",
            "//bar:main");

    nontransitiveResult.assertSuccess();
    transitiveResult.assertSuccess();

    ImmutableList<String> foundNonTransitiveTargets =
        Arrays.stream(nontransitiveResult.getStdout().split(System.lineSeparator()))
            .map(line -> line.split("\\s+")[0])
            .collect(ImmutableList.toImmutableList());
    ImmutableList<String> foundTransitiveTargets =
        Arrays.stream(transitiveResult.getStdout().split(System.lineSeparator()))
            .map(line -> line.split("\\s+")[0])
            .collect(ImmutableList.toImmutableList());

    ImmutableMap<String, String> foundNonTransitiveTargetsAndHashes =
        Arrays.stream(nontransitiveResult.getStdout().split(System.lineSeparator()))
            .collect(
                ImmutableMap.toImmutableMap(
                    line -> line.split("\\s+")[0], line -> line.split("\\s+")[1]));
    ImmutableMap<String, String> foundTransitiveTargetsAndHashes =
        Arrays.stream(transitiveResult.getStdout().split(System.lineSeparator()))
            .collect(
                ImmutableMap.toImmutableMap(
                    line -> line.split("\\s+")[0], line -> line.split("\\s+")[1]));

    assertThat(foundNonTransitiveTargets, Matchers.containsInAnyOrder("//foo:main", "//bar:main"));
    assertThat(
        foundTransitiveTargets,
        Matchers.containsInAnyOrder(
            "//foo:main",
            "//deps:dep3",
            "//deps:dep2",
            "//deps:dep1",
            "//bar:main",
            "//deps:dep4"));
    assertEquals(
        foundNonTransitiveTargetsAndHashes.get("//foo:main"),
        foundTransitiveTargetsAndHashes.get("//foo:main"));
    assertEquals(
        foundNonTransitiveTargetsAndHashes.get("//bar:main"),
        foundTransitiveTargetsAndHashes.get("//bar:main"));
  }
}
