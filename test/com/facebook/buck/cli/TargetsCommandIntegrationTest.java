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

import static com.facebook.buck.testutil.MoreAsserts.assertJsonMatches;
import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ThriftRuleKeyDeserializer;
import com.facebook.buck.util.environment.Platform;
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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.thrift.TException;
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
  public void testShowTargetsNamesWhenNoOptionsProvided() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "targets_command", tmp);
    workspace.setUp();

    ProcessResult resultAll =
        workspace.runBuckCommand(
            "targets", "-c", "parser.enable_configurable_attributes=true", "//:");
    resultAll.assertSuccess();
    assertEquals(
        ImmutableSet.of("//:A", "//:B", "//:C", "//:test-library"),
        ImmutableSet.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(resultAll.getStdout())));
  }

  @Test
  public void testOutputPath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-output", "//:test", "//:another-test");
    result.assertSuccess();
    assertEquals(
        linesToText(
            "//:another-test "
                + MorePaths.pathWithPlatformSeparators("buck-out/gen/another-test/test-output"),
            "//:test " + MorePaths.pathWithPlatformSeparators("buck-out/gen/test/test-output"),
            ""),
        result.getStdout());
  }

  @Test
  public void testConfigurationRulesNotIncludedInOutputPath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "targets_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "-c", "parser.enable_configurable_attributes=true", "--show-output", "//:");
    result.assertSuccess();
    assertEquals(
        linesToText(
            "//:A " + MorePaths.pathWithPlatformSeparators("buck-out/gen/A/A.txt"),
            "//:B " + MorePaths.pathWithPlatformSeparators("buck-out/gen/B/B.txt"),
            "//:test-library"),
        result.getStdout().trim());
  }

  @Test
  public void testConfigurationRulesWithAnnotationProcessor() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "targets_command_annotation_processor", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-output", "//:");
    result.assertSuccess();

    verifyTestConfigurationRulesWithAnnotationProcessorOutput(
        result, MorePaths::pathWithPlatformSeparators);
  }

  @Test
  public void testConfigurationRulesWithAnnotationProcessorFullOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "targets_command_annotation_processor", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-full-output", "//:");
    result.assertSuccess();

    verifyTestConfigurationRulesWithAnnotationProcessorOutput(
        result, s -> MorePaths.pathWithPlatformSeparators(tmp.getRoot().resolve(s)));
  }

  private static void verifyTestConfigurationRulesWithAnnotationProcessorOutput(
      ProcessResult result, Function<String, String> resolvePath) {
    assertEquals(
        linesToText(
            "//:annotation_processor",
            "//:annotation_processor_lib "
                + resolvePath.apply(
                    "buck-out/gen/lib__annotation_processor_lib__output/annotation_processor_lib.jar"),
            "//:test-library",
            "//:test-library-with-processing "
                + resolvePath.apply("buck-out/annotation/__test-library-with-processing_gen__"),
            "//:test-library-with-processing-with-srcs "
                + resolvePath.apply(
                    "buck-out/gen/lib__test-library-with-processing-with-srcs__output/test-library-with-processing-with-srcs.jar")
                + " "
                + resolvePath.apply(
                    "buck-out/annotation/__test-library-with-processing-with-srcs_gen__"),
            "//:test-library-with-srcs "
                + resolvePath.apply(
                    "buck-out/gen/lib__test-library-with-srcs__output/test-library-with-srcs.jar")),
        result.getStdout().trim());
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
  public void testConfigurationRulesNotIncludedInRuleKey() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "targets_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "-c", "parser.enable_configurable_attributes=true", "--show-rulekey", "//:");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(result.getStdout(), "//:A", "//:B", "//:test-library");
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
  public void testOutputWithRepoGlob() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-output", "...");
    result.assertSuccess();
    assertEquals(
        linesToText(
            "//:another-test "
                + MorePaths.pathWithPlatformSeparators("buck-out/gen/another-test/test-output"),
            "//:java_lib "
                + MorePaths.pathWithPlatformSeparators(
                    "buck-out/gen/lib__java_lib__output/java_lib.jar")
                + " "
                + MorePaths.pathWithPlatformSeparators("buck-out/annotation/__java_lib_gen__"),
            "//:plugin",
            "//:test " + MorePaths.pathWithPlatformSeparators("buck-out/gen/test/test-output"),
            ""),
        result.getStdout());
  }

  @Test
  public void testRuleKeyWithRepoGlob() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-rulekey", "...");
    result.assertSuccess();
    assertThat(
        result.getStdout().trim(),
        Matchers.matchesPattern(
            linesToText(
                "//:another-test [a-f0-9]{40}",
                "//:java_lib [a-f0-9]{40}",
                "//:plugin [a-f0-9]{40}",
                "//:test [a-f0-9]{40}")));
  }

  @Test
  public void testCellPath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-cell-path", "//:test");
    result.assertSuccess();
    assertEquals(
        "//:test "
            + MorePaths.pathWithPlatformSeparators(tmp.getRoot().toRealPath())
            + System.lineSeparator(),
        result.getStdout());
  }

  @Test
  public void testConfigurationRulesIncludedInShowCellPath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "targets_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets",
            "-c",
            "parser.enable_configurable_attributes=true",
            "--show-cell-path",
            "//:");
    result.assertSuccess();
    assertEquals(
        linesToText(
            "//:A " + MorePaths.pathWithPlatformSeparators(tmp.getRoot().toRealPath()),
            "//:B " + MorePaths.pathWithPlatformSeparators(tmp.getRoot().toRealPath()),
            "//:C " + MorePaths.pathWithPlatformSeparators(tmp.getRoot().toRealPath()),
            "//:test-library " + MorePaths.pathWithPlatformSeparators(tmp.getRoot().toRealPath())),
        result.getStdout().trim());
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
            + System.lineSeparator(),
        result.getStdout());
  }

  private ImmutableList<String> parseAndVerifyTargetsAndHashes(
      String outputLine, String... targets) {
    List<String> lines =
        Splitter.on(System.lineSeparator())
            .splitToList(CharMatcher.whitespace().trimFrom(outputLine));
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
  public void testTargetHashWithRepoGlob() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-target-hash", "...");
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
  public void testConfigurationRulesIncludedInShowTargetHash() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "targets_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets",
            "-c",
            "parser.enable_configurable_attributes=true",
            "--show-target-hash",
            "//:");
    result.assertSuccess();
    parseAndVerifyTargetsAndHashes(result.getStdout(), "//:A", "//:B", "//:C", "//:test-library");
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
    assumeFalse("Apple CodeSign doesn't work on Windows", Platform.detect() == Platform.WINDOWS);
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
    assumeFalse("Apple CodeSign doesn't work on Windows", Platform.detect() == Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "...", "--show-target-hash", "--detect-test-changes");
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
    assumeFalse("Apple CodeSign doesn't work on Windows", Platform.detect() == Platform.WINDOWS);
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
    assumeFalse("Apple CodeSign doesn't work on Windows", Platform.detect() == Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "--show-target-hash", "--detect-test-changes", "//workspace:workspace");
    result.assertSuccess();
    String hash = parseAndVerifyTargetAndHash(result.getStdout(), "//workspace:workspace");

    String fileName = "test/Test.m";
    Files.write(
        workspace.getPath(fileName),
        ("// This is not a test" + System.lineSeparator()).getBytes(UTF_8));
    ProcessResult result2 =
        workspace.runBuckCommand(
            "targets", "--show-target-hash", "--detect-test-changes", "//workspace:workspace");
    result2.assertSuccess();
    String hash2 = parseAndVerifyTargetAndHash(result2.getStdout(), "//workspace:workspace");

    assertNotEquals(hash, hash2);
  }

  @Test
  public void testTargetHashChangesAfterModifyingSourceFileForAllTargets() throws IOException {
    assumeFalse("Apple CodeSign doesn't work on Windows", Platform.detect() == Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-target-hash", "--detect-test-changes", "...");
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
    Files.write(
        workspace.getPath(fileName),
        ("// This is not a test" + System.lineSeparator()).getBytes(UTF_8));
    ProcessResult result2 =
        workspace.runBuckCommand("targets", "...", "--show-target-hash", "--detect-test-changes");
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
    assumeFalse("Apple CodeSign doesn't work on Windows", Platform.detect() == Platform.WINDOWS);
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
        ImmutableSet.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(result.getStdout())));
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

    assertJsonMatches(workspace.getFileContents("output_path_json.js"), result.getStdout());
  }

  @Test
  public void testConfigurationRulesIncludedInJsonOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "targets_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets", "-c", "parser.enable_configurable_attributes=true", "--json", "//:");
    result.assertSuccess();

    assertJsonMatches(workspace.getFileContents("output_path_json.js"), result.getStdout());
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
  public void testShowTargetsApplyDefaultFlavorsModeSingle() throws IOException {
    assumeFalse("Apple CodeSign doesn't work on Windows", Platform.detect() == Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult resultAll = workspace.runBuckCommand("targets", "...");
    resultAll.assertSuccess();
    assertEquals(
        ImmutableSet.of(
            "//bin:bin", "//bin:genrule", "//lib:lib", "//test:test", "//workspace:workspace"),
        ImmutableSet.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(resultAll.getStdout())));

    ProcessResult resultSingle = workspace.runBuckCommand("targets", "//lib:lib");
    resultSingle.assertSuccess();
    assertEquals(
        ImmutableSet.of("//lib:lib#default,static"),
        ImmutableSet.copyOf(
            Splitter.on(System.lineSeparator())
                .omitEmptyStrings()
                .split(resultSingle.getStdout())));
  }

  @Test
  public void testShowTargetsApplyDefaultFlavorsModeAll() throws IOException {
    assumeFalse("Apple CodeSign doesn't work on Windows", Platform.detect() == Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult resultAll =
        workspace.runBuckCommand("targets", "...", "--config", "project.default_flavors_mode=all");
    resultAll.assertSuccess();
    assertEquals(
        ImmutableSet.of(
            "//bin:bin",
            "//bin:genrule",
            "//lib:lib#default,static",
            "//test:test",
            "//workspace:workspace"),
        ImmutableSet.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(resultAll.getStdout())));

    ProcessResult resultSingle =
        workspace.runBuckCommand(
            "targets", "//lib:lib", "--config", "project.default_flavors_mode=all");
    resultSingle.assertSuccess();
    assertEquals(
        ImmutableSet.of("//lib:lib#default,static"),
        ImmutableSet.copyOf(
            Splitter.on(System.lineSeparator())
                .omitEmptyStrings()
                .split(resultSingle.getStdout())));
  }

  @Test
  public void testShowTargetsApplyDefaultFlavorsModeDisabled() throws IOException {
    assumeFalse("Apple CodeSign doesn't work on Windows", Platform.detect() == Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "xcode_workspace_with_tests", tmp);
    workspace.setUp();

    ProcessResult resultAll =
        workspace.runBuckCommand(
            "targets", "...", "--config", "project.default_flavors_mode=disabled");
    resultAll.assertSuccess();
    assertEquals(
        ImmutableSet.of(
            "//bin:bin", "//bin:genrule", "//lib:lib", "//test:test", "//workspace:workspace"),
        ImmutableSet.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(resultAll.getStdout())));

    ProcessResult resultSingle =
        workspace.runBuckCommand(
            "targets", "//lib:lib", "--config", "project.default_flavors_mode=disabled");
    resultSingle.assertSuccess();
    assertEquals(
        ImmutableSet.of("//lib:lib"),
        ImmutableSet.copyOf(
            Splitter.on(System.lineSeparator())
                .omitEmptyStrings()
                .split(resultSingle.getStdout())));
  }

  @Test
  public void testShowAllTargetsWithJson() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--json", "--show-output", "...");
    result.assertSuccess();

    assertJsonMatches(workspace.getFileContents("output_path_json_all.js"), result.getStdout());
  }

  @Test
  public void testShowAllTargetsWithJsonRespectsConfig() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets",
            "--json",
            "-c",
            "ui.json_attribute_format=snake_case",
            "--show-output",
            "...");
    result.assertSuccess();

    assertJsonMatches(
        workspace.getFileContents("output_path_json_all_snake_case.js"), result.getStdout());

    result =
        workspace.runBuckCommand(
            "targets", "--json", "-c", "ui.json_attribute_format=legacy", "--show-output", "...");
    result.assertSuccess();

    assertJsonMatches(workspace.getFileContents("output_path_json_all.js"), result.getStdout());
  }

  @Test
  public void testSpecificAttributesWithJson() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets",
            "--json",
            "--show-output",
            "...",
            "--output-attributes",
            "buck.outputPath",
            "name");
    result.assertSuccess();

    assertJsonMatches(
        workspace.getFileContents("output_path_json_all_filtered.js"), result.getStdout());
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
            "targets", "--json", "--show-output", "...", "--output-attributes", "srcs");
    result.assertSuccess();

    assertThat(
        result.getStdout(),
        equalTo(linesToText("[", "{", "  \"srcs\" : [ \"Foo.java\" ]", "}", "]", "")));
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
        ImmutableList.copyOf(
            Splitter.on(System.lineSeparator()).omitEmptyStrings().split(matcher.group("lines")));

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
  public void testConfigurationRulesNotIncludedInDotOutput() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "targets_command", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand(
            "targets",
            "-c",
            "parser.enable_configurable_attributes=true",
            "--dot",
            "--show-rulekey",
            "--show-transitive-rulekeys",
            "//:");
    result.assertSuccess();
    String output = result.getStdout().trim();

    assertThat(
        output,
        allOf(
            containsString(":A"),
            containsString(":B"),
            containsString(":test-library"),
            not(containsString(":C"))));
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
        extractTargetsFromOutput(nontransitiveResult.getStdout());
    ImmutableList<String> foundTransitiveTargets =
        extractTargetsFromOutput(transitiveResult.getStdout());

    ImmutableMap<String, String> foundNonTransitiveTargetsAndHashes =
        extractTargetsAndHashesFromOutput(nontransitiveResult.getStdout());
    ImmutableMap<String, String> foundTransitiveTargetsAndHashes =
        extractTargetsAndHashesFromOutput(transitiveResult.getStdout());

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

  @Test
  public void printsBothOutputAndFiltersType() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path_and_type", tmp);
    workspace.setUp();

    ProcessResult result =
        workspace.runBuckCommand("targets", "--show-output", "//:", "--type", "export_file");
    result.assertSuccess();

    String expected =
        "//:exported.txt "
            + workspace.getBuckPaths().getGenDir().resolve("exported.txt").resolve("exported.txt")
            + System.lineSeparator();
    assertEquals(expected, result.getStdout());
  }

  @Test
  public void failsIfNoTargetsProvided() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "output_path_and_type", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("targets", "--show-output");
    result.assertExitCode(null, ExitCode.COMMANDLINE_ERROR);
    assertThat(
        result.getStderr(),
        containsString(
            "Must specify at least one build target pattern. See https://buckbuild.com/concept/build_target_pattern.html"));
  }

  @Test
  public void testTargetHashesAreTheSameWithTheSameConfiguration() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "targets_command_with_configurable_attributes", tmp);
    workspace.setUp();

    ProcessResult resultWithConfigA1 =
        workspace.runBuckCommand("targets", "-c", "a.b=a", "--show-target-hash", "//:echo");
    ProcessResult resultWithConfigA2 =
        workspace.runBuckCommand("targets", "-c", "a.b=a", "--show-target-hash", "//:echo");

    resultWithConfigA1.assertSuccess();
    resultWithConfigA2.assertSuccess();

    ImmutableList<String> foundTargetsWithConfigA1 =
        extractTargetsFromOutput(resultWithConfigA1.getStdout());
    ImmutableList<String> foundTargetsWithConfigA2 =
        extractTargetsFromOutput(resultWithConfigA2.getStdout());

    ImmutableMap<String, String> foundTargetsAndHashesWithConfigA1 =
        extractTargetsAndHashesFromOutput(resultWithConfigA1.getStdout());
    ImmutableMap<String, String> foundTargetsAndHashesWithConfigA2 =
        extractTargetsAndHashesFromOutput(resultWithConfigA2.getStdout());

    assertThat(foundTargetsWithConfigA1, Matchers.containsInAnyOrder("//:echo"));
    assertThat(foundTargetsWithConfigA2, Matchers.containsInAnyOrder("//:echo"));
    assertEquals(
        foundTargetsAndHashesWithConfigA1.get("//:echo"),
        foundTargetsAndHashesWithConfigA2.get("//:echo"));
  }

  @Test
  public void testTargetHashesAreDifferentWithDistinctConfiguration() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "targets_command_with_configurable_attributes", tmp);
    workspace.setUp();

    ProcessResult resultWithConfigA =
        workspace.runBuckCommand("targets", "-c", "a.b=a", "--show-target-hash", "//:echo");
    ProcessResult resultWithConfigB =
        workspace.runBuckCommand("targets", "-c", "a.b=b", "--show-target-hash", "//:echo");

    resultWithConfigA.assertSuccess();
    resultWithConfigB.assertSuccess();

    ImmutableList<String> foundTargetsWithConfigA =
        extractTargetsFromOutput(resultWithConfigA.getStdout());
    ImmutableList<String> foundTargetsWithConfigB =
        extractTargetsFromOutput(resultWithConfigB.getStdout());

    ImmutableMap<String, String> foundTargetsAndHashesWithConfigA =
        extractTargetsAndHashesFromOutput(resultWithConfigA.getStdout());
    ImmutableMap<String, String> foundTargetsAndHashesWithConfigB =
        extractTargetsAndHashesFromOutput(resultWithConfigB.getStdout());

    assertThat(foundTargetsWithConfigA, Matchers.containsInAnyOrder("//:echo"));
    assertThat(foundTargetsWithConfigB, Matchers.containsInAnyOrder("//:echo"));
    assertNotEquals(
        foundTargetsAndHashesWithConfigA.get("//:echo"),
        foundTargetsAndHashesWithConfigB.get("//:echo"));
  }

  private static ImmutableList<String> extractTargetsFromOutput(String output) {
    return Arrays.stream(output.split(System.lineSeparator()))
        .map(line -> line.split("\\s+")[0])
        .collect(ImmutableList.toImmutableList());
  }

  private static ImmutableMap<String, String> extractTargetsAndHashesFromOutput(String output) {
    return Arrays.stream(output.split(System.lineSeparator()))
        .collect(
            ImmutableMap.toImmutableMap(
                line -> line.split("\\s+")[0], line -> line.split("\\s+")[1]));
  }
}
