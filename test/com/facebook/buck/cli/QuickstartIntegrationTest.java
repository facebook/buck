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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.junit.MatcherAssume.assumeThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;

import org.junit.Rule;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Note: because we use {@link com.facebook.buck.util.PackagedResource}, which expects zips to be
 * created by the build system, you may need to run this test through ant or buck, rather than
 * directly in IntelliJ.
 */
public class QuickstartIntegrationTest {

  @Rule
  public TemporaryPaths quickstartDirectory = new TemporaryPaths();

  @Rule
  public TemporaryPaths destDir = new TemporaryPaths();

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
        destDir.getRoot().toAbsolutePath().toString()).assertSuccess();

    ProjectWorkspace destinationWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "quickstart_expected_project",
        destDir);
    destinationWorkspace.setUp();
    destinationWorkspace.verify(); // Verifies the project was generated as expected.

    Path readme = destDir.getRoot().resolve("README.md");
    assertTrue("`buck quickstart` should create a README file.", Files.isRegularFile(readme));
    assertEquals(
        "`buck quickstart` should output the contents of the README file to standard output.",
        new String(Files.readAllBytes(readme), StandardCharsets.UTF_8),
        result.getStdout());

    Path localProp = destDir.getRoot().resolve("local.properties");
    Properties prop = new Properties();
    prop.load(Files.newInputStream(localProp));
    assertTrue(
        "`buck quickstart` should create a local.properties file.",
        Files.isRegularFile(localProp));
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

    Path buckOut = destinationWorkspace.getPath("buck-out");
    assertTrue("`buck build` should create a buck-out directory.", Files.isDirectory(buckOut));
  }

  @Test
  public void testQuickstartCreatesIosProject() throws CmdLineException, IOException {
    assumeThat(Platform.detect(), is(Platform.MACOS));
    ProjectWorkspace quickstartWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "empty_project", quickstartDirectory);
    quickstartWorkspace.setUp();

    ProcessResult result = quickstartWorkspace.runBuckCommand(
        "quickstart",
        "--type",
        "ios",
        "--dest-dir",
        destDir.getRoot().toAbsolutePath().toString()).assertSuccess();

    ProjectWorkspace destinationWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "quickstart_expected_ios_project",
        destDir);
    destinationWorkspace.setUp();
    destinationWorkspace.verify(); // Verifies the project was generated as expected.

    Path readme = destDir.getRoot().resolve("README.md");
    assertTrue("`buck quickstart` should create a README file.", Files.isRegularFile(readme));
    assertEquals(
        "`buck quickstart` should output the contents of the README file to standard output.",
        new String(Files.readAllBytes(readme), StandardCharsets.UTF_8),
        result.getStdout());

    // We can't test building if the user does not have an Android SDK. First, test targets, since
    // it does not have that dependency.
    result = destinationWorkspace.runBuckCommand("targets").assertSuccess();

    assertEquals(
        "`buck targets` should display a list of targets.",
        Joiner.on('\n').join(
            "//ios:BuckDemoApp",
            "//ios:BuckDemoAppBinary",
            "//ios:BuckDemoAppPackage",
            "//ios:BuckDemoAppResources",
            "//ios:BuckDemoAppTest") + "\n",
        result.getStdout());

    destinationWorkspace.runBuckCommand("build", "demo_app_ios").assertSuccess();

    Path buckOut = destinationWorkspace.getPath("buck-out");
    assertTrue("`buck build` should create a buck-out directory.", Files.isDirectory(buckOut));

    destinationWorkspace.runBuckCommand("project", "demo_app_ios").assertSuccess();
  }

}
