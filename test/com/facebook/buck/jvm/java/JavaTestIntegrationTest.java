/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class JavaTestIntegrationTest {

  @Rule public TemporaryPaths temp = new TemporaryPaths();

  @Test
  public void shouldNotCompileIfDependsOnCompilerClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_test_deps", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("build", "//:no-jsr-305");

    result.assertFailure();
    String stderr = result.getStderr();

    // Javac emits different errors on Windows !?!
    String lookFor;
    if (Platform.detect() == Platform.WINDOWS) {
      // Note: javac puts wrong line ending
      lookFor =
          "cannot find symbol\n"
              + "  symbol:   class Nullable\n"
              + "  location: package javax.annotation"
              + System.lineSeparator()
              + "import javax.annotation.Nullable;";
    } else {
      lookFor = "cannot find symbol" + System.lineSeparator() + "import javax.annotation.Nullable;";
    }
    assertTrue(stderr, stderr.contains(lookFor));
  }

  @Test
  public void shouldRefuseToRunJUnitTestsIfHamcrestNotOnClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_test_deps", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:no-hamcrest");

    // The bug this addresses was exposed as a missing output XML files. We expect the test to fail
    // with a warning to the user explaining that hamcrest was missing.
    result.assertTestFailure();
    String stderr = result.getStderr();
    assertTrue(
        stderr,
        stderr.contains(
            "Unable to locate hamcrest on the classpath. Please add as a test dependency."));
  }

  @Test
  public void shouldRefuseToRunJUnitTestsIfJUnitNotOnClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_test_deps", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:no-junit");

    // The bug this address was exposed as a missing output XML files. We expect the test to fail
    // with a warning to the user explaining that hamcrest was missing.
    result.assertTestFailure();
    String stderr = result.getStderr();
    assertTrue(
        stderr,
        stderr.contains(
            "Unable to locate junit on the classpath. Please add as a test dependency."));
  }

  @Test
  public void shouldRefuseToRunTestNgTestsIfTestNgNotOnClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "missing_test_deps", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:no-testng");

    result.assertTestFailure();
    String stderr = result.getStderr();
    assertTrue(
        stderr,
        stderr.contains(
            "Unable to locate testng on the classpath. Please add as a test dependency."));
  }

  /**
   * There's a requirement that the JUnitRunner creates and runs tests on the same thread (thanks to
   * jmock having a thread guard), but we don't want to create lots of threads. Because of this the
   * runner uses one SingleThreadExecutor to run all tests. However, if one test schedules another
   * (as is the case with Suites and sub-tests) _and_ the buck config says that we're going to use a
   * custom timeout for tests, then both tests are created and executed using the same single thread
   * executor, in the following order:
   *
   * <p>create suite -> create test -> run suite -> run test
   *
   * <p>Obviously, that "run test" causes the deadlock, since suite hasn't finished executing and
   * won't until test completes, but test won't be run until suite finishes. Furrfu.
   */
  @Test
  public void shouldNotDeadlock() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "deadlock", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:suite");

    result.assertSuccess();
  }

  @Test
  public void missingResultsFileIsTestFailure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "java_test_missing_result_file", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:simple");

    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    String stderr = result.getStderr();
    assertTrue(stderr, stderr.contains("test exited before generating results file"));
  }

  @Test
  public void spinningTestTimesOutGlobalTimeout() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "slow_tests", temp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[test]" + System.lineSeparator() + "  rule_timeout = 250", ".buckconfig");

    ProcessResult result = workspace.runBuckCommand("test", "//:spinning");
    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    String stderr = result.getStderr();
    assertTrue(stderr, stderr.contains("test timed out before generating results file"));
    assertThat(stderr, Matchers.containsString("FAIL"));
    assertThat(stderr, Matchers.containsString("250ms"));
  }

  @Test
  public void spinningTestTimesOutPerRuleTimeout() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "slow_tests_per_rule_timeout", temp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("test", "//:spinning");
    result.assertSpecialExitCode("test should fail", ExitCode.TEST_ERROR);
    String stderr = result.getStderr();
    assertTrue(stderr, stderr.contains("test timed out before generating results file"));
    assertThat(stderr, Matchers.containsString("FAIL"));
    assertThat(stderr, Matchers.containsString("100ms"));
  }

  @Test
  public void normalTestDoesNotTimeOut() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "slow_tests", temp);
    workspace.setUp();
    workspace.writeContentsToPath(
        "[test]" + System.lineSeparator() + "  rule_timeout = 10000", ".buckconfig");

    workspace.runBuckCommand("test", "//:slow").assertSuccess();
  }

  @Test
  public void brokenTestGivesFailedTestResult() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "java_test_broken_test", temp);
    workspace.setUp();
    workspace.runBuckCommand("test", "//:simple").assertTestFailure();
  }

  @Test
  public void staticInitializationException() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "static_initialization_test", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:npe");
    result.assertTestFailure();
    assertThat(
        result.getStderr(), Matchers.containsString("com.facebook.buck.example.StaticErrorTest"));
  }

  @Test
  public void dependencyOnAnotherTest() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "depend_on_another_test", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:a");
    result.assertSuccess();
  }

  @Test
  public void testWithJni() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_with_jni", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:jtest");
    result.assertSuccess();
  }

  @Test
  public void testWithJniWithWhitelist() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_with_jni", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:jtest-skip-dep");
    result.assertSuccess();
  }

  @Test
  public void testWithJniWithWhitelistAndDangerousSymlink() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_with_jni", temp);
    workspace.setUp();
    ProcessResult result1 =
        workspace.runBuckCommand("test", "//:jtest-pernicious", "//:jtest-symlink");
    result1.assertSuccess();

    workspace.replaceFileContents("BUCK", "\"//:jlib-native\",  #delete-1", "");
    workspace.replaceFileContents("JTestWithoutPernicious.java", "@Test // getValue", "");
    workspace.replaceFileContents("JTestWithoutPernicious.java", "// @Test//noTestLib", "@Test");

    ProcessResult result2 =
        workspace.runBuckCommand("test", "//:jtest-pernicious", "//:jtest-symlink");
    result2.assertSuccess();
  }

  @Test
  public void testForkMode() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "slow_tests", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("test", "//:fork-mode");
    result.assertSuccess();
  }

  @Test
  public void testClasspath() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "test_rule_classpath", temp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("audit", "classpath", "//:top");
    result.assertSuccess();
    ImmutableSortedSet<Path> actualPaths =
        FluentIterable.from(Arrays.asList(result.getStdout().split("\\s+")))
            .transform(input -> temp.getRoot().relativize(Paths.get(input)))
            .toSortedSet(Ordering.natural());
    ImmutableSortedSet<Path> expectedPaths =
        ImmutableSortedSet.of(
            Paths.get("buck-out/gen/lib__top__output/top.jar"),
            Paths.get("buck-out/gen/lib__direct_dep__output/direct_dep.jar"),
            Paths.get("buck-out/gen/lib__mid_test#testsjar__output/mid_test#testsjar.jar"),
            Paths.get("buck-out/gen/lib__transitive_lib__output/transitive_lib.jar"));
    assertEquals(expectedPaths, actualPaths);
  }

  @Test
  public void testEnvLocationMacro() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "env_macros", temp);
    workspace.setUp();
    workspace.runBuckCommand("test", "//:env").assertSuccess();
  }
}
