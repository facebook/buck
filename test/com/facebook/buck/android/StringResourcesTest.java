/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.TreeMap;

public class StringResourcesTest {

  private static final ImmutableMap<Integer, String> strings = ImmutableMap.of(
      12345678, "S_one",
      12345679, "S_two",
      12345680, "\\\"S_three\\\"");

  private static final ImmutableMap<String, String> plural1 = ImmutableMap.of(
      "one", "P1_one",
      "few", "P1_few",
      "many", "P1_many");

  private static final ImmutableMap<String, String> plural2 = ImmutableMap.of(
      "zero", "P2_zero",
      "two", "P2_two",
      "other", "P2_other");

  private static final ImmutableMap<Integer, ImmutableMap<String, String>> plurals =
      ImmutableMap.of(
          12345689, plural1,
          12345692, plural2);

  private static final ImmutableMap<Integer, ImmutableList<String>> arrays =
      ImmutableMap.of(
          12345694, ImmutableList.of("A1_one", "A1_two"),
          12345699, ImmutableList.of("A2_one"));


  @Test
  public void testBinaryStream() throws IOException {
    TreeMap<Integer, String> stringsMap = Maps.newTreeMap();
    stringsMap.putAll(strings);
    TreeMap<Integer, ImmutableMap<String, String>> pluralsMap = Maps.newTreeMap();
    pluralsMap.putAll(plurals);
    TreeMap<Integer, ImmutableList<String>> arraysMap = Maps.newTreeMap();
    arraysMap.putAll(arrays);
    byte[] binaryOutput = new StringResources(stringsMap, pluralsMap, arraysMap)
        .getBinaryFileContent();

    verifyBinaryStream(binaryOutput);
  }

  @Test
  public void testUnescapesQuotesAndApostrophes() {
    assertEquals("Test",
        new String(StringResources.getUnescapedStringBytes("Test")));
    assertEquals("\"testing\"",
        new String(StringResources.getUnescapedStringBytes("\\\"testing\\\"")));
    assertEquals("On a friend's timeline",
        new String(StringResources.getUnescapedStringBytes("On a friend\\'s timeline")));
  }

  private void verifyBinaryStream(byte[] binaryOutput) throws IOException {
    DataInputStream stream = new DataInputStream(new ByteArrayInputStream(binaryOutput));

    // Version
    assertEquals(1, stream.readInt());

    // Strings
    assertEquals(3, stream.readInt());
    assertEquals(12345678, stream.readInt());
    assertEquals(0, stream.readShort());
    assertEquals(5, stream.readShort());
    assertEquals(1, stream.readShort());
    assertEquals(5, stream.readShort());
    assertEquals(1, stream.readShort());
    assertEquals(9, stream.readShort());

    // string values
    assertEquals("S_one", readStringOfLength(stream, 5));
    assertEquals("S_two", readStringOfLength(stream, 5));
    assertEquals("\"S_three\"", readStringOfLength(stream, 9)); // string should be unescaped.

    // Plurals
    assertEquals(2, stream.readInt());
    assertEquals(12345689, stream.readInt());
    assertEquals(0, stream.readShort());
    assertEquals(3, stream.readByte()); // number of categories.
    assertEquals(1, stream.readByte());
    assertEquals(6, stream.readShort());
    assertEquals(3, stream.readByte());
    assertEquals(6, stream.readShort());
    assertEquals(4, stream.readByte());
    assertEquals(7, stream.readShort());

    assertEquals(3, stream.readShort());
    assertEquals(3, stream.readByte()); // number of categories.
    assertEquals(0, stream.readByte());
    assertEquals(7, stream.readShort());
    assertEquals(2, stream.readByte());
    assertEquals(6, stream.readShort());
    assertEquals(5, stream.readByte());
    assertEquals(8, stream.readShort());

    // plural strings
    assertEquals("P1_one", readStringOfLength(stream, 6));
    assertEquals("P1_few", readStringOfLength(stream, 6));
    assertEquals("P1_many", readStringOfLength(stream, 7));
    assertEquals("P2_zero", readStringOfLength(stream, 7));
    assertEquals("P2_two", readStringOfLength(stream, 6));
    assertEquals("P2_other", readStringOfLength(stream, 8));

    // Arrays
    assertEquals(2, stream.readInt());
    assertEquals(12345694, stream.readInt());
    assertEquals(0, stream.readShort());
    assertEquals(2, stream.readInt()); // number of array elements.
    assertEquals(6, stream.readShort());
    assertEquals(6, stream.readShort());
    assertEquals(5, stream.readShort());
    assertEquals(1, stream.readInt()); // number of array elements.
    assertEquals(6, stream.readShort());

    // array strings
    assertEquals("A1_one", readStringOfLength(stream, 6));
    assertEquals("A1_two", readStringOfLength(stream, 6));
    assertEquals("A2_one", readStringOfLength(stream, 6));
  }

  private String readStringOfLength(DataInputStream stream, int length) throws IOException {
    byte[] data = new byte[length];
    assertEquals(length, stream.read(data, 0, length));
    return new String(data);
  }
}
