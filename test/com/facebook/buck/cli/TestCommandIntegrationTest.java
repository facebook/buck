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

import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.PropertySaver;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class TestCommandIntegrationTest {

  private static final Path CODE_COVERAGE_SUBPATH =
      Paths.get("buck-out", "gen", "jacoco", "code-coverage");
  private ProjectWorkspace workspace;

  private static Map<String, String> getCodeCoverageProperties() {

    Path jacocoJar =
        Paths.get(
                Preconditions.checkNotNull(
                    EnvVariablesProvider.getSystemEnv().get("JACOCO_AGENT_JAR")))
            .toAbsolutePath();
    Path reportGenJar =
        Paths.get(
                Preconditions.checkNotNull(
                    EnvVariablesProvider.getSystemEnv().get("REPORT_GENERATOR_JAR")))
            .toAbsolutePath();

    return ImmutableMap.of(
        "buck.jacoco_agent_jar", jacocoJar.toString(),
        "buck.report_generator_jar", reportGenJar.toString());
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "test_coverage", tmp);
    workspace.setUp();
  }

  /*
   * We spoof system properties in the --code-coverage integration tests so that buck will look for
   * jacoco and the report generator in the correct locations in the buck repo rather than in the
   * temporary workspace. Output should still be written to the workspace's buck-out.
   */

  @Test
  public void testCsvCodeCoverage() throws Exception {
    try (PropertySaver saver = new PropertySaver(getCodeCoverageProperties())) {
      ProcessResult result =
          workspace.runBuckCommand(
              "test", "--code-coverage", "--code-coverage-format", "CSV", "//test:simple_test");
      result.assertSuccess();
    }

    assertTrue(Files.exists(workspace.getPath(CODE_COVERAGE_SUBPATH).resolve("coverage.csv")));

    workspace.runBuckCommand("clean");
    try (PropertySaver saver = new PropertySaver(getCodeCoverageProperties())) {
      ProcessResult result =
          workspace.runBuckCommand(
              "test",
              "--code-coverage",
              "--code-coverage-format",
              "CSV",
              "//test:test_setup_for_source_only_abi",
              "--config",
              "java.abi_generation_mode=source_only",
              "--config",
              "java.compile_against_abis=true");
      result.assertSuccess();
    }
    assertTrue(Files.exists(workspace.getPath(CODE_COVERAGE_SUBPATH).resolve("coverage.csv")));
  }

  @Test
  public void testHtmlCodeCoverage() throws Exception {
    workspace.enableDirCache();
    try (PropertySaver saver = new PropertySaver(getCodeCoverageProperties())) {
      ProcessResult result =
          workspace.runBuckCommand(
              "test", "--code-coverage", "--code-coverage-format", "HTML", "//test:simple_test");
      result.assertSuccess();

      // Ensure we can still find artifacts for coverage when fetched from cache.
      // We need:
      // * The actual jar files for rules under test(not just the abis)
      // * Any generated/materialized sources that went into those rules
      workspace.runBuckCommand("clean", "--keep-cache");
      workspace
          .runBuckCommand(
              "test", "--code-coverage", "--code-coverage-format", "HTML", "//test:simple_test")
          .assertSuccess();
    }

    assertTrue(Files.exists(workspace.getPath(CODE_COVERAGE_SUBPATH).resolve("index.html")));
    assertTrue(
        Files.exists(workspace.getPath(CODE_COVERAGE_SUBPATH).resolve("jacoco-sessions.html")));
  }

  @Test
  public void testXmlCodeCoverage() throws Exception {
    try (PropertySaver saver = new PropertySaver(getCodeCoverageProperties())) {
      ProcessResult result =
          workspace.runBuckCommand(
              "test", "--code-coverage", "--code-coverage-format", "XML", "//test:simple_test");

      result.assertSuccess();
    }
    assertTrue(Files.exists(workspace.getPath(CODE_COVERAGE_SUBPATH).resolve("coverage.xml")));
  }

  @Test
  public void testCodeCoverageFindsNonClasspathSources() throws Exception {
    workspace.enableDirCache();
    try (PropertySaver saver = new PropertySaver(getCodeCoverageProperties())) {
      ProcessResult result =
          workspace.runBuckCommand(
              "test",
              "--code-coverage",
              "--code-coverage-format",
              "CSV",
              "//test:wider_classpath_coverage_test");
      result.assertSuccess();

      Path coverageOutput = workspace.getPath(CODE_COVERAGE_SUBPATH).resolve("coverage.csv");
      // This file would not exist if no srcs were found
      assertTrue(Files.exists(coverageOutput));

      // Now ensure that when build results are cached, we still materialize the non-classpath deps
      workspace.runBuckCommand("clean", "--keep-cache");
      workspace
          .runBuckCommand(
              "test",
              "--code-coverage",
              "--code-coverage-format",
              "CSV",
              "//test:wider_classpath_coverage_test")
          .assertSuccess();
    }
  }

  @Test
  public void testFailsIfNoTestsProvided() throws IOException {
    ProcessResult result = workspace.runBuckCommand("test");
    result.assertExitCode(null, ExitCode.COMMANDLINE_ERROR);
    assertThat(
        result.getStderr(),
        Matchers.containsString(
            "Must specify at least one build target. See https://buck.build/concept/build_target_pattern.html"));
  }

  @Test
  public void testRunsEverythingIfAllRequested() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_coverage", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "--all");
    result.assertSuccess();
    Assert.assertThat(
        result.getStderr(),
        Matchers.matchesPattern(
            Pattern.compile(
                ".*PASS.*?MockTest" + System.lineSeparator() + ".*",
                Pattern.MULTILINE | Pattern.DOTALL)));
    Assert.assertThat(
        result.getStderr(),
        Matchers.matchesPattern(
            Pattern.compile(
                ".*PASS.*?MockTest2" + System.lineSeparator() + ".*",
                Pattern.MULTILINE | Pattern.DOTALL)));
    Assert.assertThat(result.getStderr(), Matchers.containsString("TESTS PASSED"));
  }

  @Test
  public void testLabelInclusiveFiltering() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_coverage", tmp);
    workspace.setUp();

    // Run tests of foo, but only include label A
    workspace.runBuckCommand("test", "//:foo", "--include", "A");
    // Has label A
    workspace.getBuildLog().assertTargetBuiltLocally("//test:wider_classpath_coverage_test");
    // Has labels A and B
    workspace.getBuildLog().assertTargetBuiltLocally("//test:simple_test");
    // Has label B
    workspace.getBuildLog().assertNoLogEntry("//test:test_setup_for_source_only_abi");
  }

  @Test
  public void testLabelExclusiveFiltering() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_coverage", tmp);
    workspace.setUp();

    // Run tests of foo, but exclude label B
    workspace.runBuckCommand("test", "//:foo", "--exclude", "B");
    // Has label A
    workspace.getBuildLog().assertTargetBuiltLocally("//test:wider_classpath_coverage_test");
    // Has label A and B
    workspace.getBuildLog().assertNoLogEntry("//test:simple_test");
    // Has label B
    workspace.getBuildLog().assertNoLogEntry("//test:test_setup_for_source_only_abi");
  }
}
