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
 * Describes the results of a set of tests from a specific target. This type is intended to be used
 * by external applications (like the Intellij Buck plugin) to deserialize events coming from the
 * webserver.
 */
public interface TestResultsExternalInterface<T> {
  /**
   * @return a list of TestCaseSummaryExternalInterface for external or TestCaseSummary for Buck
   * @see TestCaseSummaryExternalInterface
   */
  ImmutableList<T> getTestCases();
  /** @return the status of the dependencies tests. */
  boolean getDependenciesPassTheirTests();
  /** @return the total number of tests that ran */
  int getTotalNumberOfTests();
  /** @return the status of the tests, success of failure */
  boolean isSuccess();
  /** @return if any of the tests in this batch have an assumption violations. */
  boolean hasAssumptionViolations();
}
