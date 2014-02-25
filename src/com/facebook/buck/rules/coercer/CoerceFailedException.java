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

import java.nio.file.Path;

@SuppressWarnings("serial")
public class CoerceFailedException extends Exception {
  public CoerceFailedException(String format, Object... args) {
    super(String.format(format, args));
  }

  public static CoerceFailedException simple(
      Path pathRelativeToProjectRoot, Object object, Class<?> resultType) {
    return new CoerceFailedException("%s: Cannot coerce argument '%s' to %s",
        pathRelativeToProjectRoot, object, resultType);
  }

  public static CoerceFailedException simple(
      Path pathRelativeToProjectRoot, Object object, Class<?> resultType, String detail) {
    return new CoerceFailedException("%s: Cannot coerce argument '%s' to %s, %s",
        pathRelativeToProjectRoot, object, resultType, detail);
  }
}
