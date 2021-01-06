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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WindowsCreateProcessEscapeTest {
  @Test
  public void testCases() {
    // An array of of input strings and the expected output.
    String[][] tests = {
      {
        "C:\\Windows\\", "C:\\Windows\\",
      },
      {
        "", "\"\"",
      },
      {
        " ", "\" \"",
      },
      {
        "\t", "\"\t\"",
      },
      {
        "\\", "\\",
      },
      {
        "\\\\", "\\\\",
      },
      {
        " \\", "\" \\\\\"",
      },
      {
        "\t\\", "\"\t\\\\\"",
      },
      {
        "\\\"", "\"\\\\\\\"\"",
      },
      {
        "\\a\\\"", "\"\\a\\\\\\\"\"",
      },
      {
        "\\\"a\\\"", "\"\\\\\\\"a\\\\\\\"\"",
      },
      {
        "\\\"\\\"", "\"\\\\\\\"\\\\\\\"\"",
      },
    };

    for (String[] test : tests) {
      assertEquals(2, test.length);
      assertEquals(test[1], WindowsCreateProcessEscape.quote(test[0]));
    }
  }
}
