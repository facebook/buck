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

package com.facebook.buck.features.python;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.features.python.toolchain.impl.PythonPlatformsProviderFactoryUtils;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ParameterizedTests;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.VersionStringComparator;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PythonTestIntegrationTest {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ParameterizedTests.getPermutations(
        ImmutableList.copyOf(PythonBuckConfig.PackageStyle.values()));
  }

  @Parameterized.Parameter public PythonBuckConfig.PackageStyle packageStyle;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  public ProjectWorkspace workspace;

  private String echoTestRunner = "echo";

  @Before
  public void setUp() throws IOException {
    if (packageStyle == PythonBuckConfig.PackageStyle.INPLACE
        || packageStyle == PythonBuckConfig.PackageStyle.INPLACE_LITE) {
      assumeTrue(!Platform.detect().equals(Platform.WINDOWS));
    }

    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "python_test", tmp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[python]\npackage_style = " + packageStyle.toString().toLowerCase() + "\n", ".buckconfig");
    if (Platform.detect().equals(Platform.WINDOWS)) {
      workspace.writeContentsToPath("echo %*", workspace.getPath("echo.bat"));
      echoTestRunner = workspace.resolve("echo.bat").toAbsolutePath().toString();
    }
    PythonBuckConfig config = getPythonBuckConfig();
    assertThat(config.getPackageStyle(), equalTo(packageStyle));
  }

  @Test
  public void testXProtocolRuleWithoutFailures() throws IOException, InterruptedException {
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "--config", "test.external_runner=" + echoTestRunner, "//:testx_success");
    result.assertSuccess();

    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertSpecParsing(spec);
    ProcessExecutor.Result processResult = executeCmd(spec);

    assertEquals(0, processResult.getExitCode());

    assertTrue(processResult.getStdout().toString().contains("hello runner"));
    assertTrue(
        processResult
            .getStderr()
            .toString()
            .contains("test_that_passes (test_success.Test) ... ok"));
    assertFalse(processResult.getStderr().toString().contains("FAIL"));
    assertTrue(processResult.getStderr().toString().contains("Ran 1 test"));
  }

  @Test
  public void testXProtocolRuleWithFailures() throws IOException, InterruptedException {
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "--config", "test.external_runner=" + echoTestRunner, "//:testx_failure");
    result.assertSuccess();

    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertSpecParsing(spec);
    ProcessExecutor.Result processResult = executeCmd(spec);

    // Exit code is 70 (internal software error) when test fails
    assertEquals(70, processResult.getExitCode());

    assertTrue(processResult.getStdout().toString().contains("hello runner"));
    assertTrue(
        processResult
            .getStderr()
            .toString()
            .contains("test_that_fails (test_failure.Test) ... FAIL"));
    assertTrue(
        processResult
            .getStderr()
            .toString()
            .contains("test_that_passes (test_failure.Test) ... ok"));
    assertTrue(
        processResult
            .getStderr()
            .toString()
            .contains("test_that_passes (test_failure.Test2) ... ok"));
    assertTrue(processResult.getStderr().toString().contains("Ran 3 tests"));
  }

  @Test
  public void testXProtocolRuleWithBadTestMainModule() throws IOException, InterruptedException {
    ProcessResult result =
        workspace.runBuckCommand(
            "test",
            "--config",
            "test.external_runner=" + echoTestRunner,
            "//:testx_failure_with_test_main");
    result.assertSuccess();

    Path specOutput =
        workspace.getPath(
            workspace.getBuckPaths().getScratchDir().resolve("external_runner_specs.json"));
    JsonParser parser = ObjectMappers.createParser(specOutput);

    ArrayNode node = parser.readValueAsTree();
    JsonNode spec = node.get(0).get("specs");

    assertSpecParsing(spec);
    ProcessExecutor.Result processResult = executeCmd(spec);

    assertEquals(1, processResult.getExitCode());
    assertTrue(processResult.getStderr().toString().contains("No module named bad_test_main"));
  }

  @Test
  public void testPythonTest() throws IOException {
    // This test should pass.
    ProcessResult result1 = workspace.runBuckCommand("test", "//:test-success");
    result1.assertSuccess();
    workspace.resetBuildLogFile();

    ProcessResult runResult1 = workspace.runBuckCommand("run", "//:test-success");
    runResult1.assertSuccess("python test binary should exit 0 on success");

    // This test should fail.
    ProcessResult result2 = workspace.runBuckCommand("test", "//:test-failure");
    result2.assertTestFailure();
    assertThat(
        "`buck test` should fail because test_that_fails() failed.",
        result2.getStderr(),
        containsString("test_that_fails"));

    ProcessResult runResult2 = workspace.runBuckCommand("run", "//:test-failure");
    runResult2.assertExitCode(
        "python test binary should exit with expected error code on failure", ExitCode.TEST_ERROR);
  }

  @Test
  public void testPythonTestSelectors() throws IOException {
    ProcessResult result =
        workspace.runBuckCommand(
            "test", "--test-selectors", "Test#test_that_passes", "//:test-failure");
    result.assertSuccess();

    result =
        workspace.runBuckCommand(
            "test", "--test-selectors", "!Test#test_that_fails", "//:test-failure");
    result.assertSuccess();
    workspace.resetBuildLogFile();

    result =
        workspace.runBuckCommand(
            "test", "--test-selectors", "!test_failure.Test#", "//:test-failure");
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed"));
  }

  @Test
  public void testPythonTestEnv() {
    // Test if setting environment during test execution works
    ProcessResult result = workspace.runBuckCommand("test", "//:test-env");
    result.assertSuccess();
  }

  @Test
  public void testPythonSkippedResult() {
    assumePythonVersionIsAtLeast("2.7", "unittest skip support was added in Python-2.7");
    ProcessResult result = workspace.runBuckCommand("test", "//:test-skip").assertSuccess();
    assertThat(result.getStderr(), containsString("1 Skipped"));
  }

  @Test
  public void testPythonTestTimeout() {
    ProcessResult result = workspace.runBuckCommand("test", "//:test-spinning");
    String stderr = result.getStderr();
    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    assertTrue(stderr, stderr.contains("Following test case timed out: //:test-spinning"));
  }

  @Test
  public void testPythonSetupClassFailure() {
    assumePythonVersionIsAtLeast("2.7", "`setUpClass` support was added in Python-2.7");
    ProcessResult result = workspace.runBuckCommand("test", "//:test-setup-class-failure");
    result.assertSpecialExitCode(
        "Tests should execute successfully but fail.", ExitCode.TEST_ERROR);
    assertThat(
        result.getStderr(),
        containsString(
            "FAILURE test_setup_class_failure.Test test_that_passes: Exception: setup failure!"));
  }

  @Test
  public void testRunPythonTest() {
    ProcessResult result = workspace.runBuckCommand("run", "//:test-success");
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("test_that_passes (test_success.Test) ... ok"));
  }

  @Test
  public void testRunPythonBinaryTest() {
    ProcessResult result = workspace.runBuckCommand("test", "//:binary-with-tests");
    result.assertSuccess();
    assertThat(result.getStderr(), containsString("1 Passed"));
  }

  @Test
  public void testPythonSetupClassFailureWithTestSuite() {
    assumePythonVersionIsAtLeast("2.7", "`setUpClass` support was added in Python-2.7");
    ProcessResult result =
        workspace.runBuckCommand("test", "//:test-setup-class-failure-with-test-suite");
    result.assertSpecialExitCode(
        "Tests should execute successfully but fail.", ExitCode.TEST_ERROR);
    assertThat(
        result.getStderr(),
        containsString(
            "FAILURE test_setup_class_failure_with_test_suite.Test test_that_passes:"
                + " Exception: setup failure!"));
  }

  @Test
  public void testPythonTestCached() throws IOException {
    workspace.enableDirCache();

    // Warm the cache.
    workspace.runBuckBuild("//:test-success").assertSuccess();

    // Clean buck-out.
    workspace.runBuckCommand("clean", "--keep-cache");

    // Run the tests, which should get cache hits for everything.
    workspace.runBuckCommand("test", "//:test-success").assertSuccess();
  }

  @Test
  public void addsTargetsFromMacrosToDependencies() {
    ProcessResult result = workspace.runBuckCommand("test", "//:test-deps-with-env-macros");
    result.assertSuccess();
  }

  private void assertSpecParsing(JsonNode spec) {
    assertEquals("spec", spec.get("my").textValue());
    JsonNode other = spec.get("other");
    assertTrue(other.isArray());
    assertTrue(other.has(0));
    assertEquals("stuff", other.get(0).get("complicated").textValue());
    assertEquals(1, other.get(0).get("integer").intValue());
    assertEquals(1.2, other.get(0).get("double").doubleValue(), 0);
    assertTrue(other.get(0).get("boolean").booleanValue());
  }

  private void assumePythonVersionIsAtLeast(String expectedVersion, String message) {
    PythonVersion actualVersion =
        PythonPlatformsProviderFactoryUtils.getPythonEnvironment(
                FakeBuckConfig.builder().build(),
                new DefaultProcessExecutor(new TestConsole()),
                new ExecutableFinder())
            .getPythonVersion();
    assumeTrue(
        String.format(
            "Needs at least Python-%s, but found Python-%s: %s",
            expectedVersion, actualVersion, message),
        new VersionStringComparator().compare(actualVersion.getVersionString(), expectedVersion)
            >= 0);
  }

  private PythonBuckConfig getPythonBuckConfig() throws IOException {
    Config rawConfig = Configs.createDefaultConfig(tmp.getRoot());
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(rawConfig.getRawConfig())
            .setFilesystem(TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()))
            .build();
    return new PythonBuckConfig(buckConfig);
  }

  private ProcessExecutor.Result executeCmd(JsonNode spec)
      throws IOException, InterruptedException {
    String testScriptName = Platform.detect() == Platform.WINDOWS ? "script.bat" : "script.sh";
    String cmdKey = Platform.detect() == Platform.WINDOWS ? "cmd_win" : "cmd";

    String cmd = spec.get(cmdKey).textValue();
    Path script = Files.createTempFile("bash", testScriptName);
    Files.write(script, cmd.getBytes(Charsets.UTF_8));
    MostFiles.makeExecutable(script);
    DefaultProcessExecutor processExecutor =
        new DefaultProcessExecutor(Console.createNullConsole());
    return processExecutor.launchAndExecute(
        ProcessExecutorParams.builder().addCommand(script.toString()).build());
  }

  @Test
  public void queryTargets() {
    ProcessResult result = workspace.runBuckCommand("test", "//query_targets:t");
    result.assertSuccess();
  }
}
