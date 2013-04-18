/*
 * Copyright 2012-present Facebook, Inc.
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

import org.junit.Test;

public class EscaperTest {

  @Test
  public void testEscapeAsXmlString() {
    assertEquals("&lt;script type=&quot;text/javascript&quot;>",
        Escaper.escapeAsXmlString("<script type=\"text/javascript\">"));
    assertEquals("M&amp;M&apos;s", Escaper.escapeAsXmlString("M&M's"));
  }

  @Test
  public void testEscapeAsPythonString() {
    assertEquals("\"a\"", Escaper.escapeAsPythonString("a"));
    assertEquals("\"C:\\\\Program Files\\\\\"",
        Escaper.escapeAsPythonString("C:\\Program Files\\"));
  }

  @Test
  public void testEscapeAsBashString() {
    assertEquals("a", Escaper.escapeAsBashString("a"));
    assertEquals("'a b'", Escaper.escapeAsBashString("a b"));
    assertEquals("'a'\\''b'", Escaper.escapeAsBashString("a'b"));
    assertEquals("'$a'", Escaper.escapeAsBashString("$a"));
    assertEquals("'a\nb'", Escaper.escapeAsBashString("a\nb"));
    assertEquals("'a\tb'", Escaper.escapeAsBashString("a\tb"));
  }
}
