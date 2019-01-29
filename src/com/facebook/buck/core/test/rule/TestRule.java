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

package com.facebook.buck.core.test.rule;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.TestStatusMessage;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** A {@link BuildRule} that is designed to run tests. */
public interface TestRule extends BuildRule {

  /**
   * Callbacks to invoke during the test run to report information about test cases and/or tests.
   */
  interface TestReportingCallback {
    void testsDidBegin();

    void statusDidBegin(TestStatusMessage status);

    void statusDidEnd(TestStatusMessage status);

    void testDidBegin(String testCaseName, String testName);

    void testDidEnd(TestResultSummary testResultSummary);

    void testsDidEnd(List<TestCaseSummary> testCaseSummaries);
  }

  /** Implementation of {@link TestReportingCallback} which does nothing. */
  TestReportingCallback NOOP_REPORTING_CALLBACK =
      new TestReportingCallback() {
        @Override
        public void testsDidBegin() {}

        @Override
        public void testDidBegin(String testCaseName, String testName) {}

        @Override
        public void statusDidBegin(TestStatusMessage status) {}

        @Override
        public void statusDidEnd(TestStatusMessage status) {}

        @Override
        public void testDidEnd(TestResultSummary testResultSummary) {}

        @Override
        public void testsDidEnd(List<TestCaseSummary> testCaseSummaries) {}
      };

  /**
   * Returns the commands required to run the tests.
   *
   * <p><strong>Note:</strong> This method may be run without {@link
   * com.facebook.buck.core.build.engine.BuildEngine#build(com.facebook.buck.core.build.engine.BuildEngineBuildContext,
   * ExecutionContext, BuildRule)} having been run. This happens if the user has built [and ran] the
   * test previously and then re-runs it using the {@code --debug} flag.
   *
   * @param executionContext Provides context for creating {@link Step}s.
   * @param options The runtime testing options.
   * @param buildContext A SourcePathResolver from the build.
   * @return the commands required to run the tests
   */
  ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      BuildContext buildContext,
      TestReportingCallback testReportingCallback);

  Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      SourcePathResolver pathResolver,
      boolean isUsingTestSelectors);

  /** @return The set of labels for this build rule. */
  ImmutableSet<String> getLabels();

  /** @return The set of email addresses to act as contact points for this test. */
  ImmutableSet<String> getContacts();

  /** @return The relative path to the output directory of the test rule. */
  Path getPathToTestOutputDirectory();

  /** @return true if the test should run by itself when no other tests are run, false otherwise. */
  boolean runTestSeparately();

  /**
   * @return true if calling {@code runTests()} on this rule invokes the callbacks in {@code
   *     testReportingCallback} as the tests run, false otherwise.
   */
  boolean supportsStreamingTests();
}
