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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.util.string.AsciiBoxStringBuilder;
import com.google.common.base.Joiner;
import org.junit.Test;

public class AsciiBoxStringBuilderTest {
  private static final int MAX_LENGTH = 20;

  private AsciiBoxStringBuilder builder = new AsciiBoxStringBuilder(MAX_LENGTH);

  @Test
  public void testPutsBoxAroundLine() {
    assertEquals(
        Joiner.on('\n')
            .join(
                "+----------------------+",
                "|                      |",
                "| Hello                |",
                "|                      |",
                "+----------------------+",
                ""),
        builder.writeLine("Hello").toString());
  }

  @Test
  public void testWrapsTooLongLines() {
    assertEquals(
        Joiner.on('\n')
            .join(
                "+----------------------+",
                "|                      |",
                "| Hello world, how     |",
                "| are you doing?       |",
                "|                      |",
                "+----------------------+",
                ""),
        builder.writeLine("Hello world, how are you doing?").toString());
  }

  @Test
  public void testWrapsJustBarelyTooLongLines() {
    assertEquals(
        Joiner.on('\n')
            .join(
                "+----------------------+",
                "|                      |",
                "| Hello world, how     |",
                "| you?                 |",
                "|                      |",
                "+----------------------+",
                ""),
        builder.writeLine("Hello world, how you?").toString());
  }

  @Test
  public void testHandlesZeroLength() {
    builder = new AsciiBoxStringBuilder(0);
    assertEquals(
        Joiner.on('\n')
            .join(
                "+---+", "|   |", "| H |", "| e |", "| l |", "| l |", "| o |", "|   |", "+---+",
                ""),
        builder.writeLine("Hello").toString());
  }

  @Test
  public void testSplitsWordsThatAreTooLong() {
    builder = new AsciiBoxStringBuilder(1);
    assertEquals(
        Joiner.on('\n')
            .join(
                "+---+", "|   |", "| H |", "| e |", "| l |", "| l |", "| o |", "|   |", "| w |",
                "| o |", "| r |", "| l |", "| d |", "|   |", "+---+", ""),
        builder.writeLine("Hello world").toString());
  }
}
