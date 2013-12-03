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

package com.facebook.buck.testutil;

import com.google.common.base.Function;

import java.nio.file.Path;
import java.nio.file.Paths;

public class IdentityPathRelativizer {

  /** Utility class: do not instantiate. */
  private IdentityPathRelativizer() {}

  public static Function<String, Path> getIdentityRelativizer() {
    return new Function<String, Path>() {
      @Override
      public Path apply(String input) {
        return Paths.get(input);
      }
    };
  }
}
