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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class RobolectricTestRuleIntegrationTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() {
    // TODO(T47912516): Remove once we can upgrade our Robolectric libraries and run this on Java
    //                  11.
    Assume.assumeThat(JavaVersion.getMajorVersion(), Matchers.lessThanOrEqualTo(8));
  }

  @Test
  public void testRobolectricTestBuildsWithDummyR() throws IOException {

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.runBuckTest("//java/com/sample/lib:test").assertSuccess();
  }

  @Test
  public void testRobolectricTestWithExternalRunnerWithPassingDirectoriesInArgument()
      throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.runBuckTest("//java/com/sample/lib:test").assertSuccess();
  }

  @Test
  public void testRobolectricTestWithExternalRunnerWithPassingDirectoriesInFile()
      throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.runBuckTest("//java/com/sample/lib:test").assertSuccess();
  }

  @Test
  public void testRobolectricTestWithExternalRunnerWithRobolectricRuntimeDependencyArgument()
      throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();

    workspace.runBuckTest("//java/com/sample/lib:test_robolectric_runtime_dep").assertSuccess();
  }

  @Test
  public void robolectricTestBuildsWithBinaryResources() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.runBuckTest("//java/com/sample/lib:test_binary_resources").assertSuccess();
  }

  @Test
  public void robolectricTestXWithExternalRunner() throws Exception {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    workspace.runBuckTest("//java/com/sample/runner:robolectric_with_runner").assertSuccess();
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertEquals("spec", spec.get("my").textValue());

    JsonNode other = spec.get("other");
    assertTrue(other.isArray());
    assertTrue(other.has(0));
    assertEquals("stuff", other.get(0).get("complicated").textValue());
    assertEquals(1, other.get(0).get("integer").intValue());
    assertEquals(1.2, other.get(0).get("double").doubleValue(), 0);
    assertTrue(other.get(0).get("boolean").booleanValue());

    String cmd = spec.get("cmd").textValue();
    DefaultProcessExecutor processExecutor =
        new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.Result processResult =
        processExecutor.launchAndExecute(
            ProcessExecutorParams.builder().addCommand(cmd.split(" ")).build());
    assertEquals(0, processResult.getExitCode());
  }

  @Test
  public void robolectricTestXWithExternalRunnerWithRobolectricRuntimeDependencyArgument()
      throws Exception {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    workspace.runBuckTest("//java/com/sample/runner:robolectric_with_runner_runtime_dep");
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertEquals("spec", spec.get("my").textValue());

    JsonNode other = spec.get("other");
    assertTrue(other.isArray());
    assertTrue(other.has(0));
    assertEquals("stuff", other.get(0).get("complicated").textValue());
    assertEquals(1, other.get(0).get("integer").intValue());
    assertEquals(1.2, other.get(0).get("double").doubleValue(), 0);
    assertFalse(other.get(0).get("boolean").booleanValue());

    String cmd = spec.get("cmd").textValue();
    DefaultProcessExecutor processExecutor =
        new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.Result processResult =
        processExecutor.launchAndExecute(
            ProcessExecutorParams.builder().addCommand(cmd.split(" ")).build());
    assertEquals(0, processResult.getExitCode());
  }

  @Test
  public void robolectricTestXWithExternalRunnerWithoutRobolectricRuntimeDependencyArgument()
      throws Exception {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    workspace.addBuckConfigLocalOption("test", "external_runner", "echo");
    workspace.runBuckTest("//java/com/sample/runner:robolectric_without_runner_runtime_dep_failed");
    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertEquals("spec", spec.get("my").textValue());

    JsonNode other = spec.get("other");
    assertTrue(other.isArray());
    assertTrue(other.has(0));
    assertEquals("stuff", other.get(0).get("complicated").textValue());
    assertEquals(1, other.get(0).get("integer").intValue());
    assertEquals(1.2, other.get(0).get("double").doubleValue(), 0);
    assertTrue(other.get(0).get("boolean").booleanValue());

    String cmd = spec.get("cmd").textValue();
    DefaultProcessExecutor processExecutor =
        new DefaultProcessExecutor(Console.createNullConsole());
    ProcessExecutor.Result processResult =
        processExecutor.launchAndExecute(
            ProcessExecutorParams.builder().addCommand(cmd.split(" ")).build());
    assertEquals(1, processResult.getExitCode());
    assertTrue(processResult.getStderr().isPresent());
    assertTrue(processResult.getStderr().get().contains("java.lang.ClassNotFoundException"));
  }
}
