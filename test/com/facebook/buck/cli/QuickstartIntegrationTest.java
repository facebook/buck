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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Integration test for the {@code buck quickstart} command.
 */
public class QuickstartIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder quickstartDirectory = new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder destDir = new DebuggableTemporaryFolder();

  /**
   * Test that project is created when it is given various parameters.
   */
  @Test
  public void testQuickstartCreatesProject() throws CmdLineException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace quickstartWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "empty_project", quickstartDirectory);
    quickstartWorkspace.setUp();

    ProcessResult result = quickstartWorkspace.runBuckCommand(
        "quickstart",
        "--dest-dir",
        destDir.getRoot().getAbsolutePath()).assertSuccess();

    ProjectWorkspace destinationWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "quickstart_expected_project",
        destDir);
    destinationWorkspace.setUp();
    destinationWorkspace.verify(); // Verifies the project was generated as expected.

    File readme = new File(destDir.getRoot(), "README.md");
    assertTrue("`buck quickstart` should create a README file.", readme.isFile());
    assertEquals(
        "`buck quickstart` should output the contents of the README file to standard output.",
        Files.toString(readme, StandardCharsets.UTF_8),
        result.getStdout());

    File localProp = new File(destDir.getRoot(), "local.properties");
    Properties prop = new Properties();
    prop.load(new FileInputStream(localProp));
    assertTrue("`buck quickstart` should create a local.properties file.", localProp.isFile());
    String androidSdk = prop.getProperty("sdk.dir");
    assertTrue(
        "`buck quickstart` should put the Android SDK in the local.properties file.",
        androidSdk != null && new File(androidSdk).isDirectory());

    // We can't test building if the user does not have an Android SDK. First, test targets, since
    // it does not have that dependency.
    result = destinationWorkspace.runBuckCommand("targets").assertSuccess();

    assertEquals(
      "`buck targets` should display a list of targets.",
      Joiner.on('\n').join(
          "//apps/myapp:app",
          "//apps/myapp:debug_keystore",
          "//apps/myapp:project_config",
          "//java/com/example/activity:activity",
          "//java/com/example/activity:project_config",
          "//res/com/example/activity:project_config",
          "//res/com/example/activity:res") + "\n",
      result.getStdout());

    destinationWorkspace.runBuckCommand("build", "app").assertSuccess();

    File buckOut = destinationWorkspace.getFile("buck-out");
    assertTrue("`buck build` should create a buck-out directory.", buckOut.isDirectory());
  }
}
