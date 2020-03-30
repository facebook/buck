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

package com.facebook.buck.rules.keys;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.log.LogFormatter;
import com.facebook.buck.testutil.PlatformUtils;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
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

    String oldHash = getFileSha1Hash(workspace, "JavaLib1.java");
    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath("public class JavaLib1 { /* change */ }", "JavaLib1.java");
    String newHash = getFileSha1Hash(workspace, "JavaLib1.java");
    invokeBuckCommand(workspace, "buck-1.log");

    assertThat(
        runRuleKeyDiffer(workspace),
        Matchers.stringContainsInOrder(
            "Change details for [//:java_lib_1",
            "->jarBuildStepsFactory]",
            "  (srcs):",
            String.format("    -[path(JavaLib1.java:%s)]", oldHash),
            String.format("    +[path(JavaLib1.java:%s)]", newHash)));
  }

  @Test
  public void pathAdded() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath("public class JavaLib3 { /* change */ }", "JavaLib3.java");
    String newHash = getFileSha1Hash(workspace, "JavaLib3.java");
    invokeBuckCommand(workspace, "buck-1.log");

    assertThat(
        runRuleKeyDiffer(workspace),
        Matchers.stringContainsInOrder(
            "Change details for [//:java_lib_2",
            "->jarBuildStepsFactory]",
            "  (srcs):",
            "    -[<missing>]",
            "    -[container(LIST,len=1)]",
            "    +[container(LIST,len=2)]",
            String.format("    +[path(JavaLib3.java:%s)]", newHash)));
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

    assertThat(
        runRuleKeyDiffer(workspace),
        Matchers.stringContainsInOrder(
            "Change details for " + "[//:java_lib_2->",
            "->languageLevelOptions]",
            "  (sourceLevel):",
            "    -[string(\"6\")]",
            "    +[string(\"7\")]",
            "  (targetLevel):",
            "    -[string(\"6\")]",
            "    +[string(\"7\")]"));
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
        runRuleKeyDiffer(workspace, "//cxx:cxx_bin", ""),
        Matchers.stringContainsInOrder(
            "Change details for [//cxx:cxx_bin#compile-a.cpp.", /* hash */
            ",default",
            // We don't want this test to be sensitive to the specific paths through objects, but we
            // do want to ensure that field names are appearing.
            "->includes]",
            "(cxx/a.h):",
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
            "  (buck.deps): order of deps was name-aligned.",
            "  (buck.deps):",
            "    -[<missing>]",
            "    -[container(LIST,len=2)]",
            "    +[\"//:java_lib_3\"@ruleKey(sha1=", /* some rulekey */
            ")]",
            "    +[\"//:java_lib_3#class-abi\"@ruleKey(sha1=", /* some rulekey */
            "Change details for [//:java_lib_2",
            "->abiClasspath]",
            "  (zipFiles):",
            "    -[<missing>]",
            "    +[\"//:java_lib_3#class-abi\"@ruleKey(sha1=", /* some rulekey */
            ")]",
            ")]"));
  }

  @Test
  public void diffAll() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    String oldHash1 = getFileSha1Hash(workspace, "JavaLib1.java");
    workspace.writeContentsToPath("public class JavaLib1 { /* change */ }", "JavaLib1.java");
    workspace.writeContentsToPath("public class JavaLib3 { /* change */ }", "JavaLib3.java");
    String newHash1 = getFileSha1Hash(workspace, "JavaLib1.java");
    String newHash3 = getFileSha1Hash(workspace, "JavaLib3.java");
    invokeBuckCommand(workspace, "buck-1.log");

    String output =
        runRuleKeyDiffer(workspace, "", String.format("Rulekey divergence detected.%n"));
    assertThat(
        output,
        Matchers.stringContainsInOrder(
            "Change details for [//:java_lib_2->buildableForRuleKey->jarBuildStepsFactory]",
            "  (srcs):",
            "    -[<missing>]",
            "    -[container(LIST,len=1)]",
            "    +[container(LIST,len=2)]",
            String.format("    +[path(JavaLib3.java:%s)]", newHash3)));

    assertThat(
        output,
        Matchers.stringContainsInOrder(
            "Change details for [//:java_lib_1->buildableForRuleKey->jarBuildStepsFactory]",
            "  (srcs):",
            String.format("    -[path(JavaLib1.java:%s)]", oldHash1),
            String.format("    +[path(JavaLib1.java:%s)]", newHash1)));

    assertThat(
        output,
        Matchers.stringContainsInOrder(
            // TODO: The fact that it shows only the rule key difference for jarBuildStepsFactory
            // rather than the change in the srcs property of that class is a bug in the differ.
            "Change details for [//:java_lib_all->buildableForRuleKey]",
            "  (jarBuildStepsFactory):",
            "    -[ruleKey(sha1=" /* some rulekey */,
            "    +[ruleKey(sha1=" /* some other rulekey */));
  }

  @Test
  public void testPreprocessorSanitization() throws Exception {
    Assume.assumeTrue(Platform.detect() == Platform.MACOS);

    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, ImmutableList.of("//apple:cxx_bin"), "buck-0.log");

    Path logPath = tmp.getRoot().resolve("buck-0.log");
    String expectedFileContent = new String(Files.readAllBytes(logPath), UTF_8);
    assertThat(
        expectedFileContent,
        Matchers.containsString("string(\"-I$SDKROOT/usr/include/libxml2\"):key(sanitized):"));
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
    return runRuleKeyDiffer(workspace, "//:java_lib_2", "");
  }

  private String runRuleKeyDiffer(ProjectWorkspace workspace, String target, String expectedStderr)
      throws IOException, InterruptedException {
    ProcessExecutor.Result result =
        workspace.runCommand(
            PlatformUtils.getForPlatform().getPython2Executable(),
            Paths.get("scripts", "diff_rulekeys.py").toAbsolutePath().toString(),
            tmp.getRoot().resolve("buck-0.log").toString(),
            tmp.getRoot().resolve("buck-1.log").toString(),
            target);
    assertThat(result.getStderr(), Matchers.equalTo(Optional.of(expectedStderr)));
    assertThat(result.getExitCode(), Matchers.is(0));
    String stdout = result.getStdout().get();
    String comparingRuleString = "Comparing rules..." + System.lineSeparator();
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
    ProcessResult buckCommandResult =
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

  private String getFileSha1Hash(ProjectWorkspace projectWorkspace, String path)
      throws IOException {
    return Hashing.sha1().hashString(projectWorkspace.getFileContents(path), UTF_8).toString();
  }
}
