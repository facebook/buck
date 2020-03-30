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

/** Object that represents the failure information of a test when using idb */
@BuckStyleValue
@JsonDeserialize(as = ImmutableIdbFailureInfo.class)
public interface AbstractIdbFailureInfo {

  /** String that represents the failure message */
  String getMessage();

  /** String that represents the file which caused the failure */
  String getFile();

  /** Integer that represents the line in which the failure occured */
  int getLine();
}
