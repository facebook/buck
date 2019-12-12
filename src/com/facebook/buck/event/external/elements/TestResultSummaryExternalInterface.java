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

import com.facebook.buck.test.result.type.ResultType;
import javax.annotation.Nullable;

/**
 * Describes the results of a single test. This type is intended to be used by external applications
 * (like the Intellij Buck plugin) to deserialize events coming from the webserver.
 */
public interface TestResultSummaryExternalInterface {
  /** @return the test name. */
  String getTestName();
  /** @return the test case name. */
  String getTestCaseName();
  /**
   * Note: if deserialized from json always returns false.
   *
   * @return the success or failure of a test.
   */
  boolean isSuccess();
  /** @return the test duration in milliseconds. */
  long getTime();
  /** @return the reason for the test failure. */
  @Nullable
  String getMessage();
  /** @return the stacktrace of the test failure. */
  @Nullable
  String getStacktrace();
  /** @return the stdout of the test. */
  @Nullable
  String getStdOut();
  /** @return the stderr of the test failure. */
  @Nullable
  String getStdErr();

  /** @return the kind of test result */
  ResultType getType();
}
