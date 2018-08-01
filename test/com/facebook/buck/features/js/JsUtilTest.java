/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.js;

import static org.junit.Assert.*;

import org.junit.Test;

public class JsUtilTest {
  private static final String escaped =
      "An \\\"unescaped\\\" string with \\u0000, \\u0001, \\u0002, \\u0003, \\u0004, \\u0005, \\u0006, \\u0007, \\b, \\t, \\n, \\u000b, \\f, \\r, \\u000e, \\u000f, \\u0010, \\u0011, \\u0012, \\u0013, \\u0014, \\u0015, \\u0016, \\u0017, \\u0018, \\u0019, \\u001a, \\u001b, \\u001c, \\u001d, \\u001e, \\u001f, \\\\";
  private static final String unescaped =
      "An \"unescaped\" string with \u0000, \u0001, \u0002, \u0003, \u0004, \u0005, \u0006, \u0007, \b, \t, \n, \u000b, \f, \r, \u000e, \u000f, \u0010, \u0011, \u0012, \u0013, \u0014, \u0015, \u0016, \u0017, \u0018, \u0019, \u001a, \u001b, \u001c, \u001d, \u001e, \u001f, \\";

  @Test
  public void escapeJsonForStringEmbedding() {
    assertEquals(escaped, JsUtil.escapeJsonForStringEmbedding(unescaped));
  }
}
