/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.autodeps;

import static org.junit.Assert.assertEquals;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

public class MaybeAddVisibilityToTargetTest {

  private static String buckFile(String... lines) {
    return Stream.of(lines).collect(Collectors.joining("\n", "", "\n"));
  }

  @Test
  public void doesNothingWhenTargetIsIncluded() {
    String buckInput =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = \"foo\",",
            "\tvisibility = [",
            "\t\t\"//this:this\",",
            "\t]",
            ")");
    String expected = buckInput;
    String actual = BuckDeps.maybeAddVisibilityToTarget(buckInput, "//this:this", "//src:foo");
    assertEquals(expected, actual);
  }

  @Test
  public void doesNothingWhenTargetIncludesPUBLIC() {
    String buckInput =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = \"foo\",",
            "\tvisibility = [",
            "\t\t\"PUBLIC\",",
            "\t]",
            ")");
    String expected = buckInput;
    String actual = BuckDeps.maybeAddVisibilityToTarget(buckInput, "//this:this", "//src:foo");
    assertEquals(expected, actual);
  }

  @Test
  public void addsSameCellVisibilityWhenTargetIsAbsent() {
    String buckInput =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = \"foo\",",
            "\tvisibility = [",
            "\t\t\"//this:this\",",
            "\t]",
            ")");
    String expected =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = \"foo\",",
            "\tvisibility = [",
            "\t\t\"//other:thing\",",
            "\t\t\"//this:this\",",
            "\t]",
            ")");
    String actual =
        BuckDeps.maybeAddVisibilityToTarget(buckInput, "cell//other:thing", "cell//src:foo");
    assertEquals(expected, actual);
  }

  @Test
  public void addsVisibilityWhenTargetIsAbsent() {
    String buckInput =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = \"foo\",",
            "\tvisibility = [",
            "\t\t\"//this:this\",",
            "\t]",
            ")");
    String expected =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = \"foo\",",
            "\tvisibility = [",
            "\t\t\"to//other:thing\",",
            "\t\t\"//this:this\",",
            "\t]",
            ")");
    String actual =
        BuckDeps.maybeAddVisibilityToTarget(buckInput, "to//other:thing", "from//src:foo");
    assertEquals(expected, actual);
  }
}
