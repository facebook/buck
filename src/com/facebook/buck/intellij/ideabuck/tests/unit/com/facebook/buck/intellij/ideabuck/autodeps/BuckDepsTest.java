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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.common.base.Joiner;

import org.junit.Test;

/**
 * Unit test for methods in {@link BuckDeps}.
 */
public abstract class BuckDepsTest {

  private static String buckFile(String... lines) {
    return Joiner.on("\n").join(lines) + "\n";
  }

  public static class FindTargetInBuckFileContentsTest extends BuckDepsTest {
    @Test
    public void canFindTarget() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tdeps = [",
          "\t\t'/this',",
          "\t]",
          ")"
      );
      int expected[] = {14, 56};
      int actual[] = BuckDeps.findTargetInBuckFileContents(buckInput, "foo");
      assertArrayEquals(expected, actual);
    }

    @Test
    public void canFindTargetInMiddleOfFile() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tdeps = [",
          "\t\t'/this',",
          "\t]",
          ")",
          "rule(",
          "\tname = 'bar',",
          "\tdeps = [",
          "\t\t'/that',",
          "\t]",
          ")",
          "rule(",
          "\tname = 'baz',",
          "\tdeps = [",
          "\t\t'/other',",
          "\t]",
          ")"
      );
      int expected[] = {61, 103};
      int actual[] = BuckDeps.findTargetInBuckFileContents(buckInput, "bar");
      assertArrayEquals(expected, actual);
    }

    @Test
    public void returnsNullWhenTargetMissing() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tdeps = [",
          "\t\t'/this',",
          "\t]",
          ")",
          "rule(",
          "\tname = 'bar',",
          "\tdeps = [",
          "\t\t'/that',",
          "\t]",
          ")",
          "rule(",
          "\tname = 'baz',",
          "\tdeps = [",
          "\t\t'/other',",
          "\t]",
          ")"
      );
      assertNull(BuckDeps.findTargetInBuckFileContents(buckInput, "qux"));
    }
  }

  public static class MaybeAddDepToTargetTest extends BuckDepsTest {
    @Test
    public void addsWhenDepIsAbsent() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tdeps = [",
          "\t\t'/this',",
          "\t]",
          ")"
      );
      String expected = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tdeps = [",
          "\t\t'/other:thing',",
          "\t\t'/this',",
          "\t]",
          ")"
      );
      String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/other:thing", "foo");
      assertEquals(expected, actual);
    }

    @Test
    public void unchangedWhenDepExists() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tdeps = [",
          "\t\t'/this',",
          "\t]",
          ")"
      );
      String expected = buckInput;
      String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/this", "foo");
      assertEquals(expected, actual);
    }

    @Test
    public void unchangedWhenDepExistsInExportedDeps() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\texported_deps = [",
          "\t\t'/this',",
          "\t]",
          "\tdeps = [",
          "\t\t'/that',",
          "\t]",
          ")"
      );
      String expected = buckInput;
      String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/this", "foo");
      assertEquals(expected, actual);
    }

    @Test
    public void unchangedWhenAutoDeps() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tautodeps = True",
          "\tdeps = [",
          "\t\t'/this',",
          "\t]",
          ")"
      );
      String expected = buckInput;
      String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/that", "foo");
      assertEquals(expected, actual);
    }

    @Test
    public void unchangedWhenCantFindRule() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'bar',",
          "\tdeps = [",
          "\t\t'/this',",
          "\t]",
          ")"
      );
      String expected = buckInput;
      String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/that", "foo");
      assertEquals(expected, actual);
    }

    @Test
    public void unchangedWhenRuleIsMalformed() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tdeps = [",
          "\t\t'/this',",
          "\t]",
          "# No closing paren"
      );
      String expected = buckInput;
      String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/that", "foo");
      assertEquals(expected, actual);
    }
  }

  public static class MaybeAddVisibilityToTargetTest extends BuckDepsTest {

    @Test
    public void doesNothingWhenTargetIsIncluded() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tvisibility = [",
          "\t\t'/this',",
          "\t]",
          ")"
      );
      String expected = buckInput;
      String actual = BuckDeps.maybeAddVisibilityToTarget(buckInput, "/this", "foo");
      assertEquals(expected, actual);
    }

    @Test
    public void addsVisibilityWhenTargetIsAbsent() {
      String buckInput = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tvisibility = [",
          "\t\t'/this',",
          "\t]",
          ")"
      );
      String expected = buckFile(
          "# Comment",
          "rule(",
          "\tname = 'foo',",
          "\tvisibility = [",
          "\t\t'/other:thing',",
          "\t\t'/this',",
          "\t]",
          ")"
      );
      String actual = BuckDeps.maybeAddVisibilityToTarget(buckInput, "/other:thing", "foo");
      assertEquals(expected, actual);
    }

  }
}
