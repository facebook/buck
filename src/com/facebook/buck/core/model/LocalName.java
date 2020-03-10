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

package com.facebook.buck.core.model;

import com.google.common.base.Preconditions;

/** Build target local name utilities, {code baz} in {@code //foo/bar:baz}. */
class LocalName {

  private LocalName() {}

  static void validate(String localName) {
    Preconditions.checkArgument(!localName.isEmpty(), "Build target name must not be empty");
    Preconditions.checkArgument(
        !localName.contains("#"), "Build target name cannot contain '#' but was: %s.", localName);
  }
}
