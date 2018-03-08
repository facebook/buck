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

import java.io.File;
import java.nio.file.Paths;
import org.junit.Test;

public class EscaperTest {

  @Test
  public void testEscapeAsPythonString() {
    assertEquals("\"a\"", Escaper.escapeAsPythonString("a"));
    assertEquals(
        "\"C:\\\\Program Files\\\\\"", Escaper.escapeAsPythonString("C:\\Program Files\\"));
  }

  @Test
  public void testEscapeAsBashString() {
    assumeThat(File.separatorChar, equalTo('/'));
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

    assertThat(Escaper.escapePathForCIncludeString(Paths.get("/")), equalTo("\\\\"));
    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("some/path")), equalTo("some\\\\path"));
    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("/some/path")), equalTo("\\\\some\\\\path"));
    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("some/path/to.file")),
        equalTo("some\\\\path\\\\to.file"));
  }

  @Test
  public void testEscapePathForCIncludeStringUnix() {
    assumeThat(File.separatorChar, equalTo('/'));

    assertThat(Escaper.escapePathForCIncludeString(Paths.get("/")), equalTo("/"));
    assertThat(Escaper.escapePathForCIncludeString(Paths.get("some/path")), equalTo("some/path"));
    assertThat(Escaper.escapePathForCIncludeString(Paths.get("/some/path")), equalTo("/some/path"));
    assertThat(
        Escaper.escapePathForCIncludeString(Paths.get("some/path/to.file")),
        equalTo("some/path/to.file"));
  }

  @Test
  public void testDecodeNumericEscapeOctal1Char() {
    StringBuilder builder = new StringBuilder();
    assertEquals(
        2, Escaper.decodeNumericEscape(builder, "\\1", /*pos*/ 1, /*maxCodeLen*/ 3, /*base*/ 8));
    String str = builder.toString();
    assertEquals(1, str.length());
    assertEquals("\1", str);
  }

  @Test
  public void testDecodeNumericEscapeOctal2Char() {
    // http://en.cppreference.com/w/cpp/language/ascii
    StringBuilder builder = new StringBuilder();
    assertEquals(
        3, Escaper.decodeNumericEscape(builder, "\\43", /*pos*/ 1, /*maxCodeLen*/ 3, /*base*/ 8));
    String str = builder.toString();
    assertEquals(1, str.length());
    assertEquals("#", str);
  }

  @Test
  public void testDecodeNumericEscapeOctal3Char() {
    // http://en.cppreference.com/w/cpp/language/ascii
    StringBuilder builder = new StringBuilder();
    assertEquals(
        4, Escaper.decodeNumericEscape(builder, "\\170", /*pos*/ 1, /*maxCodeLen*/ 3, /*base*/ 8));
    String str = builder.toString();
    assertEquals(1, str.length());
    assertEquals("x", str);
  }

  @Test
  public void testDecodeNumericEscapeHex2Char() {
    // http://en.cppreference.com/w/cpp/language/ascii
    StringBuilder builder = new StringBuilder();
    assertEquals(
        4, Escaper.decodeNumericEscape(builder, "\\x53", /*pos*/ 2, /*maxCodeLen*/ 2, /*base*/ 16));
    String str = builder.toString();
    assertEquals(1, str.length());
    assertEquals("S", str);
  }

  @Test
  public void testDecodeNumericEscapeUnicode4Char() {
    // http://en.cppreference.com/w/cpp/language/ascii
    StringBuilder builder = new StringBuilder();
    assertEquals(
        6,
        Escaper.decodeNumericEscape(
            builder, "\\u0070", /*pos*/ 2, /*maxCodeLen*/ 4, /*base*/ 16, /*maxCodes*/ 2));
    String str = builder.toString();
    assertEquals(1, str.length());
    assertEquals('p', str.charAt(0));
  }

  @Test
  public void testDecodeNumericEscapeUnicode8Char() {
    // http://en.cppreference.com/w/cpp/language/ascii
    StringBuilder builder = new StringBuilder();
    assertEquals(
        10,
        Escaper.decodeNumericEscape(
            builder, "\\u00700071", /*pos*/ 2, /*maxCodeLen*/ 4, /*base*/ 16, /*maxCodes*/ 2));
    String str = builder.toString();
    assertEquals(2, str.length());
    assertEquals('p', str.charAt(0));
    assertEquals('q', str.charAt(1));
  }
}
