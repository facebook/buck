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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FindTargetInBuckFileContentsTest {
  @Test
  public void canFindTarget() {
    String buckInput =
        buckFile("# Comment", "rule(", "\tname = 'foo',", "\tdeps = [", "\t\t'/this',", "\t]", ")");
    int expected[] = {14, 56};
    int actual[] = BuckDeps.findRuleInBuckFileContents(buckInput, "foo");
    assertArrayEquals(expected, actual);
  }

  @Test
  public void canFindTargetInMiddleOfFile() {
    String buckInputSingleQuotes =
        buckFile(
            "# Comment",
            "rule(",
            "\tname = \'foo',",
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
            ")");
    int expected[] = {61, 103};
    int actual[] = BuckDeps.findRuleInBuckFileContents(buckInputSingleQuotes, "bar");
    assertArrayEquals(expected, actual);
    String buckInputDoubleQuotes = buckInputSingleQuotes.replace('\'', '\"');
    actual = BuckDeps.findRuleInBuckFileContents(buckInputDoubleQuotes, "bar");
    assertArrayEquals(expected, actual);
  }

  @Test
  public void returnsNullWhenTargetMissing() {
    String buckInput =
        buckFile(
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
            ")");
    assertNull(BuckDeps.findRuleInBuckFileContents(buckInput, "qux"));
  }
}
