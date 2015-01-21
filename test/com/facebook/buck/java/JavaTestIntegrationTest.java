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

package com.facebook.buck.java;


import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

public class JavaTestIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temp = new DebuggableTemporaryFolder();

  @Test
  public void shouldRefuseToRunJUnitTestsIfHamcrestNotOnClasspath() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "missing_test_deps",
        temp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:no-hamcrest");

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
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "missing_test_deps",
        temp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:no-junit");

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
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "missing_test_deps",
        temp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:no-testng");

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
   * create suite -> create test -> run suite -> run test
   *
   * Obviously, that "run test" causes the deadlock, since suite hasn't finished executing and won't
   * until test completes, but test won't be run until suite finishes. Furrfu.
   */
  @Test
  public void shouldNotDeadlock() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "deadlock",
        temp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:suite");

    result.assertSuccess();
  }

  @Test
  public void missingResultsFileIsTestFailure() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "java_test_missing_result_file",
        temp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:simple");

    result.assertSpecialExitCode("test should fail", 42);
    String stderr = result.getStderr();
    assertTrue(stderr, stderr.contains("test exited before generating results file"));
  }

  @Test
  public void spinningTestTimesOut() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "slow_tests",
        temp);
    workspace.setUp();
    workspace.writeContentsToPath("[test]\n  rule_timeout = 250", ".buckconfig");

    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("test", "//:spinning");
    result.assertSpecialExitCode("test should fail", 42);
    String stderr = result.getStderr();
    assertTrue(stderr, stderr.contains("test timed out before generating results file"));
  }

  @Test
  public void normalTestDoesNotTimeOut() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "slow_tests",
        temp);
    workspace.setUp();
    workspace.writeContentsToPath("[test]\n  rule_timeout = 10000", ".buckconfig");

    workspace.runBuckCommand("test", "//:slow").assertSuccess();
  }

  @Test
  public void brokenTestGivesFailedTestResult() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "java_test_broken_test",
        temp);
    workspace.setUp();
    workspace.runBuckCommand("test", "//:simple").assertTestFailure();
  }

}
