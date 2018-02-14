/*
 * Copyright 2018-present Facebook, Inc.
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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.PropertySaver;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class TestCommandIntegrationTest {

  private static final Path CODE_COVERAGE_SUBPATH =
      Paths.get("buck-out", "gen", "jacoco", "code-coverage");

  private static Map<String, String> getCodeCoverageProperties() {
    Path genDir = Paths.get("buck-out", "gen").toAbsolutePath();
    Path jacocoJar =
        genDir.resolve(Paths.get("third-party", "java", "jacoco", "__agent__", "jacocoagent.jar"));
    Path reportGenJar =
        genDir.resolve(
            Paths.get(
                "src",
                "com",
                "facebook",
                "buck",
                "jvm",
                "java",
                "coverage",
                "report-generator.jar"));

    return ImmutableMap.of(
        "buck.jacoco_agent_jar", genDir.resolve(jacocoJar).toString(),
        "buck.report_generator_jar", genDir.resolve(reportGenJar).toString());
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  /*
   * We spoof system properties in the --code-coverage integration tests so that buck will look for
   * jacoco and the report generator in the correct locations in the buck repo rather than in the
   * temporary workspace. Output should still be written to the workspace's buck-out.
   */

  @Test
  public void testCsvCodeCoverage() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_coverage", tmp, true);
    workspace.setUp();

    try (PropertySaver saver = new PropertySaver(getCodeCoverageProperties())) {
      ProcessResult result =
          workspace.runBuckCommand(
              "test", "--code-coverage", "--code-coverage-format", "CSV", "//test:simple_test");
      result.assertSuccess();
    }

    assertTrue(Files.exists(workspace.getPath(CODE_COVERAGE_SUBPATH).resolve("coverage.csv")));
  }

  @Test
  public void testHtmlCodeCoverage() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_coverage", tmp, true);
    workspace.setUp();

    try (PropertySaver saver = new PropertySaver(getCodeCoverageProperties())) {
      ProcessResult result =
          workspace.runBuckCommand(
              "test", "--code-coverage", "--code-coverage-format", "HTML", "//test:simple_test");

      result.assertSuccess();
    }

    assertTrue(Files.exists(workspace.getPath(CODE_COVERAGE_SUBPATH).resolve("index.html")));
    assertTrue(
        Files.exists(workspace.getPath(CODE_COVERAGE_SUBPATH).resolve("jacoco-sessions.html")));
  }

  @Test
  public void testXmlCodeCoverage() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_coverage", tmp, true);
    workspace.setUp();

    try (PropertySaver saver = new PropertySaver(getCodeCoverageProperties())) {
      ProcessResult result =
          workspace.runBuckCommand(
              "test", "--code-coverage", "--code-coverage-format", "XML", "//test:simple_test");

      result.assertSuccess();
    }

    assertTrue(Files.exists(workspace.getPath(CODE_COVERAGE_SUBPATH).resolve("coverage.xml")));
  }
}
