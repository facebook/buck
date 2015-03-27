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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.apple.clang.HeaderMap;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Paths;

public class HeaderMapUtils {

  private HeaderMapUtils() {
    // Static methods only, do not instantiate.
  }

  public static void assertThatHeaderMapContains(
      ImmutableMap<String, String> expected,
      HeaderMap map) {
    assertEquals(expected.size(), map.getNumEntries());
    for (String key : expected.keySet()) {
      assertEquals(
          Paths.get(expected.get(key)).toAbsolutePath().toString(),
          map.lookup(key));
    }
  }

}
