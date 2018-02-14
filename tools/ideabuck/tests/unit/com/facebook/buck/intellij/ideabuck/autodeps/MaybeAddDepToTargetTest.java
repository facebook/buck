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

import static com.facebook.buck.intellij.ideabuck.test.TestUtil.buckFile;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MaybeAddDepToTargetTest {
  @Test
  public void addsWhenDepIsAbsent() {
    String buckInput =
        buckFile("# Comment", "rule(", "\tname = 'foo',", "\tdeps = [", "\t\t'/this',", "\t]", ")");
    String expected =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = 'foo',",
            "\tdeps = [",
            "\t\t'/other:thing',",
            "\t\t'/this',",
            "\t]",
            ")");
    String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/other:thing", "foo");
    assertEquals(expected, actual);
  }

  @Test
  public void unchangedWhenDepExists() {
    String buckInput =
        buckFile("# Comment", "rule(", "\tname = 'foo',", "\tdeps = [", "\t\t'/this',", "\t]", ")");
    String expected = buckInput;
    String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/this", "foo");
    assertEquals(expected, actual);
  }

  @Test
  public void unchangedWhenDepExistsInExportedDeps() {
    String buckInput =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = 'foo',",
            "\texported_deps = [",
            "\t\t'/this',",
            "\t]",
            "\tdeps = [",
            "\t\t'/that',",
            "\t]",
            ")");
    String expected = buckInput;
    String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/this", "foo");
    assertEquals(expected, actual);
  }

  @Test
  public void unchangedWhenAutoDeps() {
    String buckInput =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = 'foo',",
            "\tautodeps = True",
            "\tdeps = [",
            "\t\t'/this',",
            "\t]",
            ")");
    String expected = buckInput;
    String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/that", "foo");
    assertEquals(expected, actual);
  }

  @Test
  public void unchangedWhenCantFindRule() {
    String buckInput =
        buckFile("# Comment", "rule(", "\tname = 'bar',", "\tdeps = [", "\t\t'/this',", "\t]", ")");
    String expected = buckInput;
    String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/that", "foo");
    assertEquals(expected, actual);
  }

  @Test
  public void unchangedWhenRuleIsMalformed() {
    String buckInput =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = 'foo',",
            "\tdeps = [",
            "\t\t'/this',",
            "\t]",
            "# No closing paren");
    String expected = buckInput;
    String actual = BuckDeps.maybeAddDepToTarget(buckInput, "/that", "foo");
    assertEquals(expected, actual);
  }
}
