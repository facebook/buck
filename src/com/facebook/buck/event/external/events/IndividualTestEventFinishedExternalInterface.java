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

package com.facebook.buck.event.external.events;

/**
 * Describes the results of running test on a single build target. This type is intended to be used
 * by external applications (like the Intellij Buck plugin) to deserialize events coming from the
 * webserver.
 */
public interface IndividualTestEventFinishedExternalInterface<T>
    extends BuckEventExternalInterface {
  // Sent when an individual test's results are available
  String RESULTS_AVAILABLE = "ResultsAvailable";
  /**
   * @return the test results available for an individual test after running buck test
   * @see com.facebook.buck.event.external.elements.TestResultsExternalInterface
   */
  T getResults();
}
