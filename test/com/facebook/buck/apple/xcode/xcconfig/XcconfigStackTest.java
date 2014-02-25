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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.FakeReadonlyProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class XcconfigStackTest {


  @Test
  public void testTrivialResolution() {
    new TestBench()
        .addLayer("FOO", "bar")
        .assertEquals(
            "__0_FOO = bar",
            "FOO = $(__0_FOO)"
        );
  }

  @Test
  public void testStackOverride() {
    new TestBench()
        .addLayer("FOO", "top")
        .addLayer("FOO", "bottom")
        .assertEquals(
            "__0_FOO = top",
            "__1_FOO = bottom",
            "FOO = $(__1_FOO)"
        );
  }

  @Test
  public void testSimpleInterpolation() {
    new TestBench()
        .addLayer(
            "FOO", "a $(BAR) b $BAR c ${BAR}",
            "BAR", "val1",
            "BAR", "val2",
            "BAZ", "$(MISSING)1")
        .assertEquals(
            "__0_BAR = val2",
            "__0_BAZ = $(MISSING)1",
            "__0_FOO = a $(BAR) b $(BAR) c $(BAR)",
            "BAR = $(__0_BAR)",
            "BAZ = $(__0_BAZ)",
            "FOO = $(__0_FOO)"
        );
  }

  @Test
  public void testInheritedInterpolation() {
    new TestBench()
        .addLayer(
            "FOO", "bottom_foo",
            "BAR", "bottom_bar",
            "BAZ", "$(FOO) $(BAR) $(BAZ)")
        .addLayer(
            "FOO", "$(inherited) top_foo",
            "BAR", "$(BAR) top_bar")
        .assertEquals(
            "__0_BAR = bottom_bar",
            "__0_BAZ = $(FOO) $(BAR) $(inherited)",
            "__0_FOO = bottom_foo",
            "__1_BAR = $(__0_BAR) top_bar",
            "__1_FOO = $(__0_FOO) top_foo",
            "BAR = $(__1_BAR)",
            "BAZ = $(__0_BAZ)",
            "FOO = $(__1_FOO)"
        );
  }

  @Test
  public void testNestedInterpolation() {
    new TestBench()
        .addLayer(
            "FOO", "a$(BAR$(FOO))",
            "NAME_SUFFIX", "_ZZZ",
            "BAR_ZZZ", "baz")
        .assertEquals(
            "__0_BAR_ZZZ = baz",
            "__0_FOO = a$(BAR$(inherited))",
            "__0_NAME_SUFFIX = _ZZZ",
            "BAR_ZZZ = $(__0_BAR_ZZZ)",
            "FOO = $(__0_FOO)",
            "NAME_SUFFIX = $(__0_NAME_SUFFIX)"
        );
  }
  @Test
  public void testConditionalSimple() {
    new TestBench()
        .addLayer(
            "FOO", "foo",
            "FOO[arch=i386]", "a",
            "FOO[arch=x86_64]", "b")
        .assertEquals(
            "__0_FOO = foo",
            "__0_FOO[arch=i386] = a",
            "__0_FOO[arch=x86_64] = b",
            "FOO = $(__0_FOO)"
        );
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
            "__0_A[arch=i386] = a_i386",
            "__0_A[arch=x86_64] = a_x86_64",
            "__0_B[sdk=iphonesimulator*] = b_sim",
            "__0_C = $(A) $(B)",
            "A = $(__0_A)",
            "B = $(__0_B)",
            "C = $(__0_C)"
        );
  }

  @Test
  public void parseErrorInIncludedFileShouldDisplayFriendlyErrorMessage() {
    ProjectFilesystem projectFilesystem = new FakeReadonlyProjectFilesystem(ImmutableMap.of(
        "foo.xcconfig", "#include \"bar.xcconfig\"",
        "bar.xcconfig", "#include \"baz.xcconfig\"",
        "baz.xcconfig", "ABC ***"
    ));
    XcconfigStack.Builder builder = XcconfigStack.builder();
    try {
      builder.addSettingsFromFile(projectFilesystem,
          ImmutableList.<Path>of(),
          Paths.get("foo.xcconfig"));
    } catch (HumanReadableException e) {
      assertThat(e.getHumanReadableErrorMessage(), containsString(
          "In file: bar.xcconfig\n" +
          "In file: baz.xcconfig\n"));
    }
    builder.build();
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
      ImmutableList<String> result = stack.resolveConfigStack();
      ImmutableList<String> expected = ImmutableList.copyOf(lines);
      Assert.assertEquals(expected, result);
      return this;
    }
  }
}

