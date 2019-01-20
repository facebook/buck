/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.MoreStringsForTests;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;

public class AuditIncludesCommandIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void includedExtensionFilesAreRecorded() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_includes", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "includes", "BUCK").assertSuccess();
    String newLine = System.lineSeparator();
    assertThat(
        result.getStdout(),
        MoreStringsForTests.equalToIgnoringPlatformNewlines(
            "# BUCK"
                + newLine
                + newLine
                + workspace.getPath("BUCK")
                + newLine
                + workspace.getPath("build_defs")
                + newLine
                + workspace.getPath("java_rules.bzl")
                + newLine));
  }

  @Test
  public void includedExtensionFilesAreRecordedInJson() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_includes", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result =
        workspace.runBuckCommand("audit", "includes", "--json", "BUCK").assertSuccess();
    ByteArrayOutputStream expectedOutput = new ByteArrayOutputStream();
    try (PrintStream sink = new PrintStream(expectedOutput);
        JsonGenerator generator = ObjectMappers.createGenerator(sink).useDefaultPrettyPrinter()) {
      ObjectMappers.WRITER.writeValue(
          generator,
          new Path[] {
            workspace.getPath("BUCK"),
            workspace.getPath("build_defs"),
            workspace.getPath("java_rules.bzl")
          });
    }
    assertEquals(expectedOutput.toString(), result.getStdout());
  }

  @Test
  public void transitiveIncludedExtensionFilesAreRecorded() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "audit_includes_transitive", tmp);
    workspace.setUp();

    // Print all of the inputs to the rule.
    ProcessResult result = workspace.runBuckCommand("audit", "includes", "BUCK").assertSuccess();
    String newLine = System.lineSeparator();
    assertThat(
        result.getStdout(),
        MoreStringsForTests.equalToIgnoringPlatformNewlines(
            "# BUCK"
                + newLine
                + newLine
                + workspace.getPath("BUCK")
                + newLine
                + workspace.getPath("build_defs")
                + newLine
                + workspace.getPath("included_build_defs")
                + newLine
                + workspace.getPath("included_java_rules.bzl")
                + newLine
                + workspace.getPath("java_rules.bzl")
                + newLine));
  }
}
