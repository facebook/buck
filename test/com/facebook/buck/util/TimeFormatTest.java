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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;

import java.util.Locale;
import org.junit.Test;

public class TimeFormatTest {

  @Test
  public void testFormatForConsole() {
    Ansi ansi = Ansi.withoutTty();

    assertEquals("<100ms", TimeFormat.formatForConsole(Locale.US, 0, ansi));
    assertEquals("<100ms", TimeFormat.formatForConsole(Locale.US, 99, ansi));

    assertEquals(" 100ms", TimeFormat.formatForConsole(Locale.US, 100, ansi));
    assertEquals(" 999ms", TimeFormat.formatForConsole(Locale.US, 999, ansi));

    assertEquals("  1.0s", TimeFormat.formatForConsole(Locale.US, 1000, ansi));
    assertEquals("  1.2s", TimeFormat.formatForConsole(Locale.US, 1200, ansi));

    assertEquals("  3,4s", TimeFormat.formatForConsole(Locale.GERMAN, 3400, ansi));
  }
}
