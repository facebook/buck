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

package com.facebook.buck.testrunner;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

/**
 * Integration test to verify that timeouts are handled as expected by {@link JUnitRunner}.
 *
 * <p>Note that there is a quirk when running tests with threads and timeouts described at
 * https://github.com/junit-team/junit/issues/686. This test verifies that Buck honors this behavior
 * of JUnit with its custom {@link BuckBlockJUnit4ClassRunner}.
 *
 * <p>That said, this behavior of JUnit interacts badly with a default test timeout in {@code
 * .buckconfig} because it requires adding {@link org.junit.rules.Timeout} to the handful of tests
 * that exploit this behavior.
 */
public class TimeoutIntegrationTest {

  private static final String PATH_TO_TIMEOUT_BEHAVIOR_TEST = "TimeoutChangesBehaviorTest.java";

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void testThatTimeoutsInTestsWorkAsExpected() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "timeouts", temporaryFolder);
    workspace.setUp();

    // ExceedsAnnotationTimeoutTest should fail.
    ProcessResult exceedsAnnotationTimeoutTestResult =
        workspace.runBuckCommand("test", "//:ExceedsAnnotationTimeoutTest");
    exceedsAnnotationTimeoutTestResult.assertTestFailure("Test should fail due to timeout");
    assertThat(
        exceedsAnnotationTimeoutTestResult.getStderr(),
        containsString(
            "FAILURE com.example.ExceedsAnnotationTimeoutTest testShouldFailDueToExpiredTimeout: "
                + "test timed out after 1000 milliseconds"));

    // TimeoutChangesBehaviorTest should pass.
    ProcessResult timeoutTestWithoutTimeout =
        workspace.runBuckCommand("test", "//:TimeoutChangesBehaviorTest");
    timeoutTestWithoutTimeout.assertSuccess();

    // TimeoutChangesBehaviorTest with @Test(timeout) specified should fail.
    // See https://github.com/junit-team/junit/issues/686 about why it fails.
    modifyTimeoutInTestAnnotation(PATH_TO_TIMEOUT_BEHAVIOR_TEST, /* addTimeout */ true);
    ProcessResult timeoutTestWithTimeoutOnAnnotation =
        workspace.runBuckCommand("test", "//:TimeoutChangesBehaviorTest");
    timeoutTestWithTimeoutOnAnnotation.assertTestFailure();
    assertThat(
        timeoutTestWithTimeoutOnAnnotation.getStderr(),
        containsString(
            "FAILURE com.example.TimeoutChangesBehaviorTest "
                + "testTimeoutDictatesTheSuccessOfThisTest: Database should have an open transaction "
                + "due to setUp()."));

    // TimeoutChangesBehaviorTest with @Rule(Timeout) should pass.
    modifyTimeoutInTestAnnotation(PATH_TO_TIMEOUT_BEHAVIOR_TEST, /* addTimeout */ false);
    insertTimeoutRule(PATH_TO_TIMEOUT_BEHAVIOR_TEST);
    ProcessResult timeoutTestWithTimeoutRule =
        workspace.runBuckCommand("test", "//:TimeoutChangesBehaviorTest");
    timeoutTestWithTimeoutRule.assertSuccess();

    workspace.verify();
  }

  @Test
  public void individualTestCanOverrideTheDefaultTestTimeout() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "overridden_timeouts", temporaryFolder);
    workspace.setUp();

    // The .buckconfig in that workspace sets the default timeout to 1000ms.
    ProcessResult result = workspace.runBuckCommand("test", "//:test");

    result.assertSuccess();
  }

  @Test
  public void testThatTimeoutsDumpsThreadStacks() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "timeouts", temporaryFolder);
    workspace.setUp();

    ProcessResult testResult = workspace.runBuckCommand("test", "//:SleepTest");

    assertThat(
        testResult.getStderr(),
        containsString("at com.example.SleepTest.testSleepABunch(SleepTest.java:"));
  }

  /**
   * Swaps all instances of {@code @Test} with {@code @Test(timeout = 10000)} in the specified Java
   * file, as determined by the value of {@code addTimeout}.
   */
  private void modifyTimeoutInTestAnnotation(String path, boolean addTimeout) throws IOException {
    Function<String, String> transform =
        line -> {
          String original = addTimeout ? "@Test" : "@Test(timeout = 100000)";
          String replacement = addTimeout ? "@Test(timeout = 100000)" : "@Test";
          return line.replace(original, replacement) + '\n';
        };
    rewriteFileWithTransform(path, transform);
  }

  /**
   * Inserts the following after the top-level class declaration:
   * <pre>
   *   @org.junit.Rule
   *   public org.junit.rules.Timeout timeoutForTests = new org.junit.rules.Timeout(10000);
   * </pre>
   */
  private void insertTimeoutRule(String path) throws IOException {
    Function<String, String> transform =
        line -> {
          if (line.startsWith("public class")) {
            return line
                + "\n\n"
                + "  @org.junit.Rule\n"
                + "  public org.junit.rules.Timeout timeoutForTests = "
                + "new org.junit.rules.Timeout(10000);\n";
          } else {
            return line + '\n';
          }
        };
    rewriteFileWithTransform(path, transform);
  }

  /**
   * Finds the file at the specified path, transforms all of its lines using the specified {@code
   * transform} parameter, and writes the transformed lines back to the path.
   */
  private void rewriteFileWithTransform(String path, Function<String, String> transform)
      throws IOException {
    Path javaFile = temporaryFolder.getRoot().resolve(path);
    List<String> lines = Files.readAllLines(javaFile, Charsets.UTF_8);
    String java = lines.stream().map(transform).collect(Collectors.joining(""));
    Files.write(javaFile, java.getBytes(Charsets.UTF_8));
  }
}
