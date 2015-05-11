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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import org.junit.Test;

import java.io.File;
import java.nio.file.Paths;

public class EscaperTest {

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
    assertEquals("''", Escaper.escapeAsBashString(""));
  }

  @Test
  public void testHex() {
    assertEquals("41", Escaper.hex('A'));
    assertEquals('A', Integer.parseInt(Escaper.hex('A'), /* radix */ 16));

    assertEquals("61", Escaper.hex('a'));
    assertEquals('a', Integer.parseInt(Escaper.hex('a'), /* radix */ 16));
  }

  @Test
  public void testEscapeMetaCharactersInPythonString() {
    String metaChars = "\n\r\t\b\f";
    assertEquals("\"\\n\\r\\t\\b\\f\"", Escaper.escapeAsPythonString(metaChars));
  }

  @Test
  public void testEscapeQuotesInPythonString() {
    String metaChars = "\"'\"";
    assertEquals("\"\\\"\\'\\\"\"", Escaper.escapeAsPythonString(metaChars));
  }

  @Test
  public void testEscapeUnicodeCharacters() {
    assertEquals("\"\\u0001\"", Escaper.escapeAsPythonString(String.valueOf('\u0001')));
    assertEquals("\"\\u0010\"", Escaper.escapeAsPythonString(String.valueOf('\u0010')));
    assertEquals("\"\\u0080\"", Escaper.escapeAsPythonString(String.valueOf('\u0080')));
    assertEquals("\"\\u0100\"", Escaper.escapeAsPythonString(String.valueOf('\u0100')));
    assertEquals("\"\\u1000\"", Escaper.escapeAsPythonString(String.valueOf('\u1000')));
  }

  @Test
  public void testEscapeMakefileValues() {
    assertEquals("hello world", Escaper.escapeAsMakefileValueString("hello world"));
    assertEquals("hello\\#world", Escaper.escapeAsMakefileValueString("hello#world"));
    assertEquals("hello\\\\\\#world", Escaper.escapeAsMakefileValueString("hello\\#world"));
    assertEquals("hello\\world", Escaper.escapeAsMakefileValueString("hello\\world"));
  }

  @Test
  public void testEscapePathForCIncludeStringWindows() {
    assumeThat(File.separatorChar, equalTo('\\'));

    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("/")),
        equalTo("\\\\"));
    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("some/path")),
        equalTo("some\\\\path"));
    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("/some/path")),
        equalTo("\\\\some\\\\path"));
    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("some/path/to.file")),
        equalTo("some\\\\path\\\\to.file"));
  }

  @Test
  public void testEscapePathForCIncludeStringUnix() {
    assumeThat(File.separatorChar, equalTo('/'));

    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("/")),
        equalTo("/"));
    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("some/path")),
        equalTo("some/path"));
    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("/some/path")),
        equalTo("/some/path"));
    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("some/path/to.file")),
        equalTo("some/path/to.file"));
  }
}
