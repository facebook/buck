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

package com.facebook.buck.rules.keys;

import static org.junit.Assert.assertThat;

import com.facebook.buck.log.LogFormatter;
import com.facebook.buck.log.Logger;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DiffRuleKeysScriptIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private Logger ruleKeyBuilderLogger;
  private Level previousRuleKeyBuilderLevel;
  private int lastPositionInLog;

  @Before
  public void enableVerboseRuleKeys() throws Exception {
    lastPositionInLog = 0;
    ruleKeyBuilderLogger = Logger.get(RuleKeyBuilder.class);
    previousRuleKeyBuilderLevel = ruleKeyBuilderLogger.getLevel();
    ruleKeyBuilderLogger.setLevel(Level.FINER);
    Path fullLogFilePath = tmp.getRoot().resolve(getLogFilePath());
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
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath("public class JavaLib1 { /* change */ }", "JavaLib1.java");
    invokeBuckCommand(workspace, "buck-1.log");

    String expectedResult =
        Joiner.on('\n')
            .join(
                "Change details for [//:java_lib_1]",
                "  (srcs):",
                "    -[path(JavaLib1.java:e3506ff7c11f638458d08120d54f186dc79ddada)]",
                "    +[path(JavaLib1.java:7d82c86f964af479abefa21da1f19b1030649314)]",
                "");
    assertThat(runRuleKeyDiffer(workspace), Matchers.equalTo(expectedResult));
  }

  @Test
  public void pathAdded() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath("public class JavaLib3 { /* change */ }", "JavaLib3.java");
    invokeBuckCommand(workspace, "buck-1.log");

    String expectedResult =
        Joiner.on('\n')
            .join(
                "Change details for [//:java_lib_2]",
                "  (srcs):",
                "    -[<missing>]",
                "    -[container(LIST,len=1)]",
                "    +[container(LIST,len=2)]",
                "    +[path(JavaLib3.java:3396c5e71e9fad8e8f177af9d842f1b9b67bfb46)]",
                "");
    assertThat(runRuleKeyDiffer(workspace), Matchers.equalTo(expectedResult));
  }

  @Test
  public void javacOptionsChanged() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    writeBuckConfig(workspace, "6");
    invokeBuckCommand(workspace, "buck-0.log");
    writeBuckConfig(workspace, "7");
    invokeBuckCommand(workspace, "buck-1.log");

    String expectedResult =
        Joiner.on('\n')
            .join(
                "Change details for " + "[//:java_lib_2->compileStepFactory->javacOptions]",
                "  (sourceLevel):",
                "    -[string(\"6\")]",
                "    +[string(\"7\")]",
                "  (targetLevel):",
                "    -[string(\"6\")]",
                "    +[string(\"7\")]",
                "");
    assertThat(runRuleKeyDiffer(workspace), Matchers.equalTo(expectedResult));
  }

  @Test
  public void cxxHeaderChanged() throws Exception {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, ImmutableList.of("//cxx:cxx_bin"), "buck-0.log");
    workspace.writeContentsToPath("// Different contents", "cxx/a.h");
    invokeBuckCommand(workspace, ImmutableList.of("//cxx:cxx_bin"), "buck-1.log");

    assertThat(
        runRuleKeyDiffer(workspace, "//cxx:cxx_bin"),
        Matchers.stringContainsInOrder(
            "Change details for [//cxx:cxx_bin#compile-a.cpp.", /* hash */
            ",default->preprocessDelegate->includes]",
            "(include(cxx/a.h)):",
            "-[path(cxx/a.h:", /*hash*/
            ")]",
            "+[path(cxx/a.h:", /*hash*/
            ")]"));
  }

  @Test
  public void dependencyAdded() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath(
        Joiner.on('\n')
            .join(
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

    assertThat(
        runRuleKeyDiffer(workspace),
        Matchers.stringContainsInOrder(
            "Change details for [//:java_lib_2]",
            "  (abiClasspath):",
            "    -[<missing>]",
            "    +[\"//:java_lib_3#class-abi\"@ruleKey(sha1=", /* some rulekey */
            ")]",
            "  (buck.declaredDeps):",
            "    -[<missing>]",
            "    +[\"//:java_lib_3\"@ruleKey(sha1=", /* some rulekey */
            ")]",
            "  (buck.extraDeps):",
            "    -[<missing>]",
            "    +[\"//:java_lib_3#class-abi\"@ruleKey(sha1=", /* some rulekey */
            ")]"));
  }

  @Test
  public void diffAll() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath("public class JavaLib1 { /* change */ }", "JavaLib1.java");
    workspace.writeContentsToPath("public class JavaLib3 { /* change */ }", "JavaLib3.java");
    invokeBuckCommand(workspace, "buck-1.log");

    assertThat(
        runRuleKeyDiffer(workspace, ""),
        Matchers.stringContainsInOrder(
            "Change details for [//:java_lib_all]",
            "  (srcs):",
            "    -[<missing>]",
            "    +[container(LIST,len=1)]",
            "    +[path(JavaLib3.java:3396c5e71e9fad8e8f177af9d842f1b9b67bfb46)]",
            "Change details for [//:java_lib_2]",
            "  (srcs):",
            "    -[<missing>]",
            "    -[container(LIST,len=1)]",
            "    +[container(LIST,len=2)]",
            "    +[path(JavaLib3.java:3396c5e71e9fad8e8f177af9d842f1b9b67bfb46)]",
            "Change details for [//:java_lib_1]",
            "  (srcs):",
            "    -[path(JavaLib1.java:e3506ff7c11f638458d08120d54f186dc79ddada)]",
            "    +[path(JavaLib1.java:7d82c86f964af479abefa21da1f19b1030649314)]"));
  }

  private void writeBuckConfig(ProjectWorkspace projectWorkspace, String javaVersion)
      throws Exception {
    projectWorkspace.writeContentsToPath(
        Joiner.on('\n')
            .join("[java]", "source_level = " + javaVersion, "target_level = " + javaVersion),
        ".buckconfig");
  }

  private String runRuleKeyDiffer(ProjectWorkspace workspace)
      throws IOException, InterruptedException {
    return runRuleKeyDiffer(workspace, "//:java_lib_2");
  }

  private String runRuleKeyDiffer(ProjectWorkspace workspace, String target)
      throws IOException, InterruptedException {
    String cmd = Platform.detect() == Platform.WINDOWS ? "python" : "python2.7";
    ProcessExecutor.Result result =
        workspace.runCommand(
            cmd,
            Paths.get("scripts", "diff_rulekeys.py").toAbsolutePath().toString(),
            tmp.getRoot().resolve("buck-0.log").toString(),
            tmp.getRoot().resolve("buck-1.log").toString(),
            target);
    assertThat(result.getStderr(), Matchers.equalTo(Optional.of("")));
    assertThat(result.getExitCode(), Matchers.is(0));
    String stdout = result.getStdout().get();
    String comparingRuleString = "Comparing rules...\n";
    int i = stdout.indexOf(comparingRuleString);
    Preconditions.checkState(i != -1);
    return stdout.substring(i + comparingRuleString.length());
  }

  private void invokeBuckCommand(ProjectWorkspace workspace, String logOut) throws IOException {
    invokeBuckCommand(workspace, ImmutableList.of("//:"), logOut);
  }

  private void invokeBuckCommand(
      ProjectWorkspace workspace, ImmutableList<String> targets, String logOut) throws IOException {
    ImmutableList<String> args = ImmutableList.of("targets", "--show-rulekey");
    ProjectWorkspace.ProcessResult buckCommandResult =
        workspace.runBuckCommand(
            Stream.concat(args.stream(), targets.stream()).toArray(String[]::new));

    buckCommandResult.assertSuccess();
    String fullLogContents = workspace.getFileContents(getLogFilePath());
    String logContentsForThisInvocation = fullLogContents.substring(lastPositionInLog);
    lastPositionInLog += logContentsForThisInvocation.length();
    workspace.writeContentsToPath(logContentsForThisInvocation, logOut);
  }

  private Path getLogFilePath() {
    return tmp.getRoot().resolve("buck.test.log");
  }
}
