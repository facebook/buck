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

package com.facebook.buck.event.external.elements;

import com.google.common.collect.ImmutableList;

/**
 * Describes the results of a set of tests contained in a single file. This type is intended to be
 * used by external applications (like the Intellij Buck plugin) to deserialize events coming from
 * the webserver.
 */
public interface TestCaseSummaryExternalInterface<T> {
  /** @return the test case name */
  String getTestCaseName();
  /** @return the total running time of the tests in milliseconds. */
  long getTotalTime();
  /**
   * @return the test results as a list of TestResultSummaryExternalInterface for external and
   *     TestResultSummary for Buck.
   * @see TestResultSummaryExternalInterface
   */
  ImmutableList<T> getTestResults();
  /** @return the number of skipped tests. */
  int getSkippedCount();
  /** @return the number of failed tests. */
  int getFailureCount();
  /** @return the final status of the tests. */
  boolean isSuccess();
  /** @return if any of the tests has an assumption violation. */
  boolean hasAssumptionViolations();
}
