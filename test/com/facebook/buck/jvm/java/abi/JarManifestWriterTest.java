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

package com.facebook.buck.jvm.java.abi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class JarManifestWriterTest {

  private JarManifestWriter manifestWriter;
  private StringWriter stringWriter;

  @Before
  public void setUp() {
    stringWriter = new StringWriter();
    manifestWriter = new JarManifestWriter(stringWriter);
  }

  @Test
  public void testShortLinesWrittenOnOneLine() throws IOException {
    assertEntryWrittenAs("Key: value\r\n", "Key", "value");
  }

  @Test
  public void testLongLineSplit() throws IOException {
    assertEntryWrittenAs(
        "12345678: 69-char value + 8 char key + 2 char padding = 79 chars.    |\r\n" +
            " next line\r\n",
        "12345678",
        "69-char value + 8 char key + 2 char padding = 79 chars.    |" +
            "next line");
  }

  @Test
  public void testReallyLongLineSplit() throws IOException {
    assertEntryWrittenAs(
        "12345678: 138-char value + 8 char key + 2 char padding = 148 chars.  |\r\n" +
            " 69-character second line                                            |\r\n" +
            " last line\r\n",
        "12345678",
        "138-char value + 8 char key + 2 char padding = 148 chars.  |" +
            "69-character second line                                            |" +
            "last line");
  }

  @Test
  public void testLinesSplitLikeJavaImpl() throws IOException {
    final String entryName = "test";
    final String key = "12345678";
    final String value = "138-char value + 8 char key + 2 char padding = 148 chars.  |" +
        "69-character second line                                            |" +
        "last line";

    manifestWriter.writeLine();
    manifestWriter.writeEntry("Name", entryName);
    manifestWriter.writeEntry(key, value);
    manifestWriter.writeLine();

    Manifest jdkManifest = new Manifest();
    Attributes attrs = new Attributes();
    attrs.putValue(key, value);
    jdkManifest.getEntries().put(entryName, attrs);

    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    jdkManifest.write(expected);

    assertArrayEquals(
        expected.toByteArray(),
        stringWriter.toString().getBytes(StandardCharsets.UTF_8));
  }

  private void assertEntryWrittenAs(String expected, String key, String value) throws IOException {
    manifestWriter.writeEntry(key, value);
    assertEquals(expected, stringWriter.toString());
  }
}
