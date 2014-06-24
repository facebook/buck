/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

@SuppressWarnings("serial")
public class CoerceFailedException extends Exception {

  public CoerceFailedException(String message) {
    super(message);
  }

  public CoerceFailedException(String message, Throwable cause) {
    super(message, cause);
  }

  public static CoerceFailedException simple(Object object, Class<?> resultType) {
    return new CoerceFailedException(
        String.format("cannot coerce '%s' to %s", object, resultType));
  }

  public static CoerceFailedException simple(Object object, Class<?> resultType, String detail) {
    return new CoerceFailedException(
        String.format("cannot coerce '%s' to %s, %s", object, resultType, detail));
  }

}
