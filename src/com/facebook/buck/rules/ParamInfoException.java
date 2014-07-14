/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

/**
 * Exception type thrown from `ParamInfo` methods. May originate from `ParamInfo` code (e.g. for
 * an unexpected null error) or wrap lower level errors (e.g. `CoerceFailedException`) to attach
 * parameter specific information (e.g. parameter name).
 */
@SuppressWarnings("serial")
public class ParamInfoException extends Exception {

  public ParamInfoException(String parameterName, String message) {
    super(String.format("parameter '%s': %s", parameterName, message));
  }

  public ParamInfoException(String parameterName, String message, Throwable throwable) {
    super(String.format("parameter '%s': %s", parameterName, message), throwable);
  }

}
