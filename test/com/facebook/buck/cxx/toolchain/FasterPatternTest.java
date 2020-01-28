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

package com.facebook.buck.cxx.toolchain;

import static org.junit.Assert.*;

import java.util.regex.Pattern;
import org.junit.Test;

public class FasterPatternTest {

  private void compat(String pattern, String input) {
    assertEquals(
        "pattern: '" + pattern + "', input: '" + input + "'",
        Pattern.compile(pattern, Pattern.DOTALL).matcher(input).matches(),
        FasterPattern.compile(pattern).matches(input));
  }

  @Test
  public void test() {
    String[] patterns = {
      ".*",
      ".*.*",
      ".*..*",
      ".*/.*",
      ".*/foo/bar/.*",
      ".*/foo/.*bar",
      "/foo/bar.*",
      "/foo/bar/.*",
      "\n",
      "\\n",
      ".*[ab]",
    };
    String[] inputs = {
      "/foo/bar",
      "/foo/bar/",
      "baz/foo/bar/",
      "/foo/bar/baz",
      "qux/foo/bar/baz",
      "\n",
      "\\n",
      "foo\nbar",
    };
    for (String pattern : patterns) {
      for (String input : inputs) {
        compat(pattern, input);
      }
    }
  }
}
