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

package com.facebook.buck.test;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.test.result.type.ResultType;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Test utility to build TestResults objects for use in test cases. */
public class FakeTestResults {
  // Utility class, do not instantiate.
  private FakeTestResults() {}

  private static final BuildTarget DUMMY_TARGET_FOR_TESTING =
      BuildTargetFactory.newInstance(Paths.get("/does/not/exist"), "//foo/bar", "baz");

  public static TestResults of(Iterable<? extends TestCaseSummary> testCases) {
    return withTestLogs(testCases, ImmutableList.of());
  }

  public static TestResults withTestLogs(
      Iterable<? extends TestCaseSummary> testCases, Iterable<Path> testLogs) {
    return TestResults.builder()
        .setBuildTarget(DUMMY_TARGET_FOR_TESTING)
        .setTestCases(testCases)
        .setTestLogPaths(testLogs)
        .build();
  }

  public static TestResults newFailedInstance(String name) {
    String testCaseName = name;
    TestResultSummary testResultSummary =
        new TestResultSummary(testCaseName, null, ResultType.FAILURE, 0, null, null, null, null);
    TestCaseSummary testCase =
        new TestCaseSummary(testCaseName, ImmutableList.of(testResultSummary));
    ImmutableList<TestCaseSummary> testCases = ImmutableList.of(testCase);
    return FakeTestResults.of(testCases);
  }
}
