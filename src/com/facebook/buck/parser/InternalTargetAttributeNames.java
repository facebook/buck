/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.parser;

/** Contains names of attributes that are provided by Buck and cannot be set by users. */
public class InternalTargetAttributeNames {

  /** Location of the target relative to the root of the repository */
  public static final String BASE_PATH = "buck.base_path";

  /** All the dependencies of a target. */
  public static final String DIRECT_DEPENDENCIES = "buck.direct_dependencies";
}
