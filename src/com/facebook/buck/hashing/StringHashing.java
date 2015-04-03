/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.hashing;

import com.google.common.hash.Hasher;

import java.nio.charset.StandardCharsets;

public class StringHashing {
  // Utility class, do not instantiate.
  private StringHashing() { }

  /**
   * Encodes the string in UTF-8, then hashes the length of the encoded
   * UTF-8 bytes followed by the bytes themselves.
   *
   * Useful to ensure hash codes are different when multiple strings
   * are hashed in order ("foo" then "bar" should hash differently from "foobar").
   */
  public static void hashStringAndLength(Hasher hasher, String string) {
    byte[] utf8Bytes = string.getBytes(StandardCharsets.UTF_8);
    hasher.putInt(utf8Bytes.length);
    hasher.putBytes(utf8Bytes);
  }
}
