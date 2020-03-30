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

package com.facebook.buck.apple;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/** Object that represents the xctest result when using idb */
@BuckStyleValue
@JsonDeserialize(as = ImmutableIdbTestResult.class)
public interface AbstractIdbTestResult {

  /** String that represents the name of the test bundle */
  String getBundleName();

  /** String that represents the name of the class of the test */
  String getClassName();

  /** String that represents the name of the method of the test */
  String getMethodName();

  /** Array of strings that show the logs of the test */
  String[] getLogs();

  /** Time spent executing the test */
  Float getDuration();

  /** Boolean that shows if the test passed or not */
  Boolean getPassed();

  /** Boolean that shows if the test crashed or not */
  Boolean getCrashed();

  /** Object that represents the failure information */
  ImmutableIdbFailureInfo getFailureInfo();

  /** Array of strings that show the activity logs */
  String[] getActivityLogs();
}
