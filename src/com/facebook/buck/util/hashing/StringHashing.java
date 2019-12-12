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

package com.facebook.buck.util.hashing;

import com.google.common.hash.Hasher;

public class StringHashing {
  // Utility class, do not instantiate.
  private StringHashing() {}

  /**
   * Encodes the length of the string in UTF-16 code units, then the UTF-16 code units of the
   * string.
   *
   * <p>Useful to ensure hash codes are different when multiple strings are hashed in order ("foo"
   * then "bar" should hash differently from "foobar").
   */
  public static void hashStringAndLength(Hasher hasher, String string) {
    // We used to hash the UTF-8 bytes of the string, but it takes
    // a lot of unnecessary CPU and memory to do so.
    hasher.putInt(string.length());
    hasher.putUnencodedChars(string);
  }
}
