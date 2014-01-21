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

package com.facebook.buck.apple.xcode.xcconfig;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class XcconfigStackTest {
  @Test
  public void testTrivialResolution() {
    new TestBench()
        .addLayer("FOO", "bar")
        .assertEquals("FOO = bar");
  }

  @Test
  public void testStackOverride() {
    new TestBench()
        .addLayer("FOO", "top")
        .addLayer("FOO", "bottom")
        .assertEquals("FOO = top");
  }

  @Test
  public void testSimpleInterpolation() {
    new TestBench()
        .addLayer(
            "FOO", "a $(BAR) b $BAR c",
            "BAR", "val",
            "BAZ", "$(MISSING)1")
        .assertEquals(
            "BAR = val",
            "BAZ = $(MISSING)1",
            "FOO = a val b val c");
  }

  @Test
  public void testInheritedInterpolation() {
    new TestBench()
        .addLayer(
            "FOO", "$(inherited) top_foo",
            "BAR", "$(BAR) top_bar")
        .addLayer(
            "FOO", "bottom_foo",
            "BAR", "bottom_bar",
            "BAZ", "$(FOO) $(BAR)")
        .assertEquals(
            "BAR = bottom_bar top_bar",
            "BAZ = bottom_foo top_foo bottom_bar top_bar",
            "FOO = bottom_foo top_foo");
  }

  @Test
  public void testNestedInterpolation() {
    new TestBench()
        .addLayer(
            "FOO", "a$(BAR$(NAME_SUFFIX))",
            "NAME_SUFFIX", "_ZZZ",
            "BAR_ZZZ", "baz")
        .assertEquals(
            "BAR_ZZZ = baz",
            "FOO = abaz",
            "NAME_SUFFIX = _ZZZ");
  }
  @Test
  public void testConditionalSimple() {
    new TestBench()
        .addLayer(
            "FOO", "foo",
            "FOO[arch=i386]", "a",
            "FOO[arch=x86_64]", "b")
        .assertEquals(
            "FOO = foo",
            "FOO[arch=i386] = a",
            "FOO[arch=x86_64] = b");
  }

  @Test
  public void testConditionalPropagation() {
    new TestBench()
        .addLayer(
            "FOO", "foo",
            "FOO[arch=i386]", "foo_i386",
            "BAR", "$(FOO)")
        .assertEquals(
            "BAR = foo",
            "BAR[arch=i386] = foo_i386",
            "FOO = foo",
            "FOO[arch=i386] = foo_i386");
  }

  @Test
  public void testConditionalInteraction() {
    new TestBench()
        .addLayer(
            "A[arch=i386]", "a_i386",
            "A[arch=x86_64]", "a_x86_64",
            "B[sdk=iphonesimulator*]", "b_sim",
            "C", "$A $B")
        .assertEquals(
            "A[arch=i386] = a_i386",
            "A[arch=x86_64] = a_x86_64",
            "B[sdk=iphonesimulator*] = b_sim",
            "C[arch=i386,sdk=iphonesimulator*] = a_i386 b_sim",
            "C[arch=x86_64,sdk=iphonesimulator*] = a_x86_64 b_sim");
  }

  /**
   * Helper class to reduce boilerplate for tests
   */
  private class TestBench {
    final XcconfigStack.Builder builder = XcconfigStack.builder();

    public TestBench addLayer(String... keyOrValues) {
      String key = null;
      for (String keyOrValue : keyOrValues) {
        if (key == null) {
          key = keyOrValue;
        } else {
          builder.addSetting(key, keyOrValue);
          key = null;
        }
      }
      builder.pushLayer();
      return this;
    }

    public TestBench assertEquals(String... lines) {
      XcconfigStack stack = builder.build();
      List<String> result = stack.resolveConfigStack();
      Collections.sort(result);
      Arrays.sort(lines);
      assertThat(result, contains(lines));
      return this;
    }
  }
}

