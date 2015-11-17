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

package com.facebook.buck.rules;

import static org.junit.Assert.assertThat;

import com.facebook.buck.log.LogFormatter;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DiffRuleKeysScriptIntegrationTest {

  private static final Path LOG_FILE_PATH = BuckConstant.LOG_PATH.resolve("buck.log");

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();
  private Logger ruleKeyBuilderLogger;
  private Level previousRuleKeyBuilderLevel;

  @Before
  public void enableVerboseRuleKeys() throws Exception {
    ruleKeyBuilderLogger = Logger.getLogger(RuleKeyBuilder.class.getName());
    previousRuleKeyBuilderLevel = ruleKeyBuilderLogger.getLevel();
    ruleKeyBuilderLogger.setLevel(Level.FINER);
    Path fullLogFilePath = tmp.getRootPath().resolve(LOG_FILE_PATH);
    Files.createDirectories(fullLogFilePath.getParent());
    FileHandler handler = new FileHandler(fullLogFilePath.toString());
    handler.setFormatter(new LogFormatter());
    ruleKeyBuilderLogger.addHandler(handler);
  }

  @After
  public void disableVerboseRuleKeys() {
    ruleKeyBuilderLogger.setLevel(previousRuleKeyBuilderLevel);
  }

  @Test
  public void fileContentsChanged() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath(
        "public class JavaLib1 { /* change */ }",
        "JavaLib1.java");
    invokeBuckCommand(workspace, "buck-1.log");

    String expectedResult = Joiner.on('\n').join(
        "Change details for [//:java_lib_1]",
        "  (srcs):",
        "    -[path(JavaLib1.java:e3506ff7c11f638458d08120d54f186dc79ddada)]",
        "    +[path(JavaLib1.java:7d82c86f964af479abefa21da1f19b1030649314)]",
        "");
    assertThat(
        runRuleKeyDiffer(workspace).getStdout(),
        Matchers.equalTo(Optional.of(expectedResult)));
  }

  @Test
  public void javacOptionsChanged() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    writeBuckConfig(workspace, "6");
    invokeBuckCommand(workspace, "buck-0.log");
    writeBuckConfig(workspace, "7");
    invokeBuckCommand(workspace, "buck-1.log");

    String expectedResult = Joiner.on('\n').join(
        "Change details for " +
            "[//:java_lib_1#abi->javacStepFactory.appendableSubKey->javacOptions.appendableSubKey]",
        "  (sourceLevel):",
        "    -[string(\"6\")]",
        "    +[string(\"7\")]",
        "  (targetLevel):",
        "    -[string(\"6\")]",
        "    +[string(\"7\")]",
        "");
    assertThat(
        runRuleKeyDiffer(workspace).getStdout(),
        Matchers.equalTo(Optional.of(expectedResult)));
  }

  @Test
  public void dependencyAdded() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath(
        Joiner.on('\n').join(
            "java_library(",
            "  name = 'java_lib_1',",
            "  srcs = [ 'JavaLib1.java'],",
            ")",
            "java_library(",
            "  name = 'java_lib_3',",
            "  srcs = ['JavaLib1.java'],",
            "  deps = [':java_lib_1']",
            ")",
            "java_library(",
            "  name = 'java_lib_2',",
            "  srcs = ['JavaLib2.java'],",
            "  deps = [':java_lib_1', ':java_lib_3']",
            ")"),
        "BUCK");
    invokeBuckCommand(workspace, "buck-1.log");

    // Adding new deps is not handled too well currently.
    String expectedResult = Joiner.on('\n').join(
        "Change details for [//:java_lib_2->buck.extraDeps]",
        "  (buck.declaredDeps):",
        "    -[ruleKey(sha1=454975950470ca0fec62cead356d3296654c23bf)]\n" +
        "    +[ruleKey(sha1=074b2ee17296c7b072fe8053eba19f669d432a40)]\n" +
        "Change details for [//:java_lib_2->buck.extraDeps]",
        "  (buck.declaredDeps):",
        "    -[ruleKey(sha1=454975950470ca0fec62cead356d3296654c23bf)]\n" +
        "    +[ruleKey(sha1=074b2ee17296c7b072fe8053eba19f669d432a40)]\n" +
        "  (name):",
        "    -[string(\"//:java_lib_1#abi\")]",
        "    +[string(\"//:java_lib_3#abi\")]",
        "");
    assertThat(
        runRuleKeyDiffer(workspace).getStdout(),
        Matchers.equalTo(Optional.of(expectedResult)));
  }

  private void writeBuckConfig(
      ProjectWorkspace projectWorkspace,
      String javaVersion) throws Exception {
    projectWorkspace.writeContentsToPath(
        Joiner.on('\n').join(
            "[java]",
            "source_level = " + javaVersion,
            "target_level = " + javaVersion),
        ".buckconfig");

  }

  private ProcessExecutor.Result runRuleKeyDiffer(
      ProjectWorkspace workspace) throws IOException, InterruptedException {
    ProcessExecutor.Result result = workspace.runCommand(
        "python2.7",
        Paths.get("scripts", "diff_rulekeys.py").toString(),
        tmp.getRootPath().resolve("buck-0.log").toString(),
        tmp.getRootPath().resolve("buck-1.log").toString(),
        "//:java_lib_2");
    assertThat(result.getStderr(), Matchers.equalTo(Optional.of("")));
    assertThat(result.getExitCode(), Matchers.is(0));
    return result;
  }

  private void invokeBuckCommand(ProjectWorkspace workspace, String logOut) throws IOException {
    ProjectWorkspace.ProcessResult buckCommandResult = workspace.runBuckCommand(
        "targets",
        "--show-rulekey",
        "//:java_lib_2");
    buckCommandResult.assertSuccess();
    workspace.copyFile(LOG_FILE_PATH.toString(), logOut);
    workspace.writeContentsToPath("", LOG_FILE_PATH.toString());
  }

}
