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

package com.facebook.buck.skylark.io.impl;

import static org.junit.Assert.*;

import com.facebook.buck.core.path.ForwardRelativePath;
import org.junit.Test;

public class UnixGlobPatternTest {
  @Test
  public void segmentMatches() {
    assertTrue(UnixGlobPattern.segmentMatches("*", "a", null));
    assertTrue(UnixGlobPattern.segmentMatches("*a", "a", null));
    assertFalse(UnixGlobPattern.segmentMatches("*", ".a", null));
  }

  @Test
  public void matches() {
    assertTrue(UnixGlobPattern.parse("*").matches(ForwardRelativePath.of("a"), null));
    assertFalse(UnixGlobPattern.parse("*").matches(ForwardRelativePath.of("a/b"), null));
    assertTrue(UnixGlobPattern.parse("*/a/*").matches(ForwardRelativePath.of("c/a/b"), null));
    assertFalse(UnixGlobPattern.parse("*/a/*").matches(ForwardRelativePath.of("c/x/b"), null));
    assertFalse(UnixGlobPattern.parse("*/a/*").matches(ForwardRelativePath.of("c/a"), null));
    assertFalse(UnixGlobPattern.parse("*/a").matches(ForwardRelativePath.of("c/a/b"), null));

    assertTrue(UnixGlobPattern.parse("**").matches(ForwardRelativePath.of(""), null));
    assertTrue(UnixGlobPattern.parse("**").matches(ForwardRelativePath.of("a"), null));
    assertTrue(UnixGlobPattern.parse("**").matches(ForwardRelativePath.of("a/b"), null));

    assertTrue(UnixGlobPattern.parse("a/**").matches(ForwardRelativePath.of("a"), null));
    assertTrue(UnixGlobPattern.parse("a/**").matches(ForwardRelativePath.of("a/b"), null));
    assertFalse(UnixGlobPattern.parse("a/**").matches(ForwardRelativePath.of("c"), null));
    assertFalse(UnixGlobPattern.parse("a/**").matches(ForwardRelativePath.of("c/a"), null));
    assertFalse(UnixGlobPattern.parse("a/**").matches(ForwardRelativePath.of("c/a/b"), null));

    assertTrue(UnixGlobPattern.parse("a/**/b").matches(ForwardRelativePath.of("a/b"), null));
    assertTrue(UnixGlobPattern.parse("a/**/b").matches(ForwardRelativePath.of("a/x/b"), null));
    assertTrue(UnixGlobPattern.parse("a/**/b").matches(ForwardRelativePath.of("a/x/y/b"), null));

    assertFalse(UnixGlobPattern.parse("a/**/b").matches(ForwardRelativePath.of("a/x/y/b/z"), null));
    assertFalse(UnixGlobPattern.parse("a/**/b").matches(ForwardRelativePath.of("a"), null));
    assertFalse(UnixGlobPattern.parse("a/**/b").matches(ForwardRelativePath.of("a/x"), null));

    assertFalse(UnixGlobPattern.parse("a/**/*").matches(ForwardRelativePath.of("a"), null));
    assertTrue(UnixGlobPattern.parse("a/**/*").matches(ForwardRelativePath.of("a/b"), null));
    assertTrue(UnixGlobPattern.parse("a/**/*").matches(ForwardRelativePath.of("a/b/c"), null));

    assertTrue(UnixGlobPattern.parse("**/a/**").matches(ForwardRelativePath.of("a"), null));
    assertTrue(UnixGlobPattern.parse("**/a/**").matches(ForwardRelativePath.of("x/a"), null));
    assertTrue(UnixGlobPattern.parse("**/a/**").matches(ForwardRelativePath.of("x/y/a"), null));
    assertTrue(UnixGlobPattern.parse("**/a/**").matches(ForwardRelativePath.of("a/x"), null));
    assertTrue(UnixGlobPattern.parse("**/a/**").matches(ForwardRelativePath.of("a/x/y"), null));
    assertTrue(UnixGlobPattern.parse("**/a/**").matches(ForwardRelativePath.of("x/a/y"), null));

    assertFalse(UnixGlobPattern.parse("**/a/**").matches(ForwardRelativePath.of("x"), null));
    assertFalse(UnixGlobPattern.parse("**/a/**").matches(ForwardRelativePath.of("x/y"), null));
    assertFalse(UnixGlobPattern.parse("**/a/**").matches(ForwardRelativePath.of("x/y/z"), null));
  }
}
