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

package com.facebook.buck.util.console;

import static org.junit.Assert.*;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.util.Ansi;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class ConsoleUtilsTest {

  @Test
  public void formatConsoleEvent() {

    assertEquals(
        ImmutableList.of(),
        ConsoleUtils.formatConsoleEvent(ConsoleEvent.severe(""), Ansi.forceTty()));
    assertEquals(
        ImmutableList.of(),
        ConsoleUtils.formatConsoleEvent(ConsoleEvent.severe(""), Ansi.withoutTty()));
    assertEquals(
        ImmutableList.of(Ansi.forceTty().asHighlightedFailureText("a")),
        ConsoleUtils.formatConsoleEvent(ConsoleEvent.severe("a"), Ansi.forceTty()));
    assertEquals(
        ImmutableList.of("a"),
        ConsoleUtils.formatConsoleEvent(ConsoleEvent.severe("a"), Ansi.withoutTty()));
    assertEquals(
        ImmutableList.of(Ansi.forceTty().asHighlightedFailureText("a")),
        ConsoleUtils.formatConsoleEvent(ConsoleEvent.severe("a\n"), Ansi.forceTty()));
    assertEquals(
        ImmutableList.of("a"),
        ConsoleUtils.formatConsoleEvent(ConsoleEvent.severe("a\r\n"), Ansi.withoutTty()));
    assertEquals(
        ImmutableList.of(Ansi.forceTty().asWarningText("a"), Ansi.forceTty().asWarningText("b")),
        ConsoleUtils.formatConsoleEvent(ConsoleEvent.warning("a\nb"), Ansi.forceTty()));
    assertEquals(
        ImmutableList.of("a", "b"),
        ConsoleUtils.formatConsoleEvent(ConsoleEvent.severe("a\r\nb\r\n"), Ansi.withoutTty()));
  }
}
