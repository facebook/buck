/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class ParserIntegrationTest {
  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void testParserFilesAreSandboxed() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "parser_with_method_overrides", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult buildResult = workspace.runBuckCommand(
        "build", "//:base_genrule", "-v", "2");
    buildResult.assertSuccess();

    workspace.verify();
  }

  /**
   * If a rule contains an erroneous dep to a non-existent rule, then it should throw an
   * appropriate message to help the user find the source of his error.
   */
  @Test
  public void testParseRuleWithBadDependency() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "parse_rule_with_bad_dependency",
        temporaryFolder);
    workspace.setUp();

    try {
      workspace.runBuckCommand("build", "//:base");
    } catch (HumanReadableException e) {
      assertTrue(
          e.getHumanReadableErrorMessage(),
          e.getHumanReadableErrorMessage().matches(
              "The build file that should contain //:bad-dep has already been parsed \\(" +
                  ".*\\), but //:bad-dep was not found. " +
                  "Please make sure that //:bad-dep is defined in " +
                  ".*\\."));
      return;
    }
    fail("An exception should have been thrown because of a bad dependency.");
  }

  /**
   * Creates the following graph (assume all / and \ indicate downward pointing arrows):
   * <pre>
   *         A
   *       /   \
   *     B       C <----|
   *   /   \   /        |
   * D       E          |
   *   \   /            |
   *     F --------------
   * </pre>
   * Note that there is a circular dependency from C -> E -> F -> C that should be caught by the
   * parser.
   */
  @Test
  public void testCircularDependencyDetection() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "circular_dependency_detection",
        temporaryFolder);
    workspace.setUp();

    try {
      workspace.runBuckCommand("build", "//:A");
    } catch (HumanReadableException e) {
      assertEquals(e.getHumanReadableErrorMessage(), "Cycle found: //:F -> //:C -> //:E -> //:F");
      return;
    }
    fail("An exception should have been thrown because of a circular dependency.");
  }
}
