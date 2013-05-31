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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/**
 * Integration test for the {@code buck project} command.
 */
public class ProjectIntegrationTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private ProjectWorkspace workspace;

  @Test
  public void testBuckProject() throws IOException {
    File templateDir = new File("test/com/facebook/buck/cli/testdata/project1/");
    File destDir = temporaryFolder.getRoot();
    workspace = new ProjectWorkspace(templateDir, destDir);
    workspace.setUp();

    CapturingPrintStream stdout = new CapturingPrintStream();
    CapturingPrintStream stderr = new CapturingPrintStream();

    Main main = new Main(stdout, stderr);
    int exitCode = main.runMainWithExitCode(destDir, "project");
    assertEquals("buck project should exit cleanly", 0, exitCode);

    workspace.verify();

    String stdErrOutput = stderr.getContentsAsString(Charsets.UTF_8);
    assertTrue(
        "Nothing should be written to stderr except possibly an Android platform warning.",
        stdErrOutput.isEmpty() || stdErrOutput.startsWith("No Android platform target specified."));
    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
          "MODIFIED FILES:",
          ".idea/compiler.xml",
          ".idea/libraries/guava.xml",
          ".idea/libraries/jsr305.xml",
          ".idea/libraries/junit.xml",
          ".idea/modules.xml",
          ".idea/runConfigurations/Debug_Buck_test.xml",
          "modules/dep1/module_modules_dep1.iml",
          "modules/tip/module_modules_tip.iml"
        ) + '\n',
        stdout.getContentsAsString(Charsets.UTF_8));
  }

  @After
  public void tearDown() throws IOException {
    if (workspace != null) {
      workspace.tearDown();
    }
  }
}
