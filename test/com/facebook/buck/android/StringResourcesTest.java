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

import com.facebook.buck.android.StringResources.Gender;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.TreeMap;
import org.junit.Test;

public class StringResourcesTest {

  private static final EnumMap<Gender, String> genderStrings1 = Maps.newEnumMap(Gender.class);
  private static final EnumMap<Gender, String> genderStrings2 = Maps.newEnumMap(Gender.class);
  private static final EnumMap<Gender, String> genderStrings3 = Maps.newEnumMap(Gender.class);

  private static final EnumMap<Gender, ImmutableMap<String, String>> genderPluralMap1 =
      Maps.newEnumMap(Gender.class);
  private static final EnumMap<Gender, ImmutableMap<String, String>> genderPluralMap2 =
      Maps.newEnumMap(Gender.class);

  private static final EnumMap<Gender, ImmutableList<String>> genderArray1 =
      Maps.newEnumMap(Gender.class);
  private static final EnumMap<Gender, ImmutableList<String>> genderArray2 =
      Maps.newEnumMap(Gender.class);

  static {
    genderStrings1.put(Gender.unknown, "S_one");
    genderStrings1.put(Gender.female, "S_one_female");

    genderStrings2.put(Gender.unknown, "S_two");

    genderStrings3.put(Gender.unknown, "S_ne");
    genderStrings3.put(Gender.female, "\\\"S_three\\\"");

    // populate first gender plural map
    genderPluralMap1.put(
        Gender.unknown,
        ImmutableMap.of(
            "one", "P1_one",
            "few", "P1_few",
            "many", "P1_many"));
    genderPluralMap1.put(
        Gender.female,
        ImmutableMap.of(
            "one", "P1_one_f1",
            "few", "P1_few_f1",
            "many", "P1_many_f1"));
    genderPluralMap1.put(
        Gender.male,
        ImmutableMap.of(
            "one", "P1_one_m2",
            "few", "P1_few_m2",
            "many", "P1_many_m2"));

    // populate second gender plural map
    genderPluralMap2.put(
        Gender.unknown,
        ImmutableMap.of(
            "zero", "P2_zero",
            "two", "P2_two",
            "other", "P2_other"));
    genderPluralMap2.put(
        Gender.female,
        ImmutableMap.of(
            "zero", "P2_zero_f1",
            "two", "P2_two_f1",
            "other", "P2_other_f1"));

    // populate gender arrays
    genderArray1.put(Gender.unknown, ImmutableList.of("A1_one", "A1_two"));
    genderArray1.put(Gender.female, ImmutableList.of("A1_one_f1", "A1_two_f1"));
    genderArray1.put(Gender.male, ImmutableList.of("A1_one_m2", "A1_two_m2"));

    genderArray2.put(Gender.unknown, ImmutableList.of("A2_one"));
    genderArray2.put(Gender.male, ImmutableList.of("A2_one_m2"));
  }

  private static final ImmutableMap<Integer, EnumMap<Gender, String>> strings =
      ImmutableMap.of(
          12345678, genderStrings1,
          12345679, genderStrings2,
          12345680, genderStrings3);

  private static final ImmutableMap<Integer, EnumMap<Gender, ImmutableMap<String, String>>>
      plurals =
          ImmutableMap.of(
              12345689, genderPluralMap1,
              12345692, genderPluralMap2);

  private static final ImmutableMap<Integer, EnumMap<Gender, ImmutableList<String>>> arrays =
      ImmutableMap.of(
          12345694, genderArray1,
          12345699, genderArray2);

  @Test
  public void testBinaryStream() throws IOException {
    TreeMap<Integer, EnumMap<Gender, String>> stringsMap = new TreeMap<>(strings);
    TreeMap<Integer, EnumMap<Gender, ImmutableMap<String, String>>> pluralsMap =
        new TreeMap<>(plurals);
    TreeMap<Integer, EnumMap<Gender, ImmutableList<String>>> arraysMap = new TreeMap<>(arrays);
    byte[] binaryOutput =
        new StringResources(stringsMap, pluralsMap, arraysMap).getBinaryFileContent();

    verifyBinaryStream(binaryOutput);
  }

  @Test
  public void testUnescapesQuotesAndApostrophes() {
    assertEquals("Test", new String(StringResources.getUnescapedStringBytes("Test")));
    assertEquals(
        "\"testing\"", new String(StringResources.getUnescapedStringBytes("\\\"testing\\\"")));
    assertEquals(
        "On a friend's timeline",
        new String(StringResources.getUnescapedStringBytes("On a friend\\'s timeline")));
  }

  private void verifyBinaryStream(byte[] binaryOutput) throws IOException {
    DataInputStream stream = new DataInputStream(new ByteArrayInputStream(binaryOutput));

    // Version
    assertEquals(2, stream.readInt());

    // Strings
    assertEquals(3, stream.readInt()); // number of strings
    assertEquals(12345678, stream.readInt()); // res id of first string

    assertEquals(0, stream.readShort()); // delta of id for first element
    assertEquals(2, stream.readByte()); // number of genders
    assertEquals(0, stream.readByte()); // ordinal of the gender enum for first string
    assertEquals(5, stream.readShort()); // length of first string of first element
    assertEquals(1, stream.readByte()); // ordinal of the gender enum for the second string
    assertEquals(12, stream.readShort()); // length of second string of first element

    assertEquals(1, stream.readShort()); // delta for second element
    assertEquals(1, stream.readByte()); // number of genders
    assertEquals(0, stream.readByte()); // ordinal of the gender enum for first string
    assertEquals(5, stream.readShort()); // length of first string

    assertEquals(1, stream.readShort()); // delta for third element
    assertEquals(2, stream.readByte()); // number of genders
    assertEquals(0, stream.readByte()); // ordinal of the gender enum for first string
    assertEquals(4, stream.readShort()); // length of first string of first element
    assertEquals(1, stream.readByte()); // ordinal of the gender enum for the second string
    assertEquals(9, stream.readShort()); // length of second string of first element

    // string values
    assertEquals("S_one", readStringOfLength(stream, 5));
    assertEquals("S_one_female", readStringOfLength(stream, 12));
    assertEquals("S_two", readStringOfLength(stream, 5));
    assertEquals("S_ne", readStringOfLength(stream, 4));
    assertEquals("\"S_three\"", readStringOfLength(stream, 9)); // string should be unescaped.

    // Plurals
    assertEquals(2, stream.readInt()); // number of plurals
    assertEquals(12345689, stream.readInt()); // res Id of the first string

    assertEquals(0, stream.readShort()); // delta of id for first element
    assertEquals(3, stream.readByte()); // number of genders

    assertEquals(0, stream.readByte()); // ordinal of the gender enum for first string
    assertEquals(3, stream.readByte()); // number of categories.
    assertEquals(1, stream.readByte()); // plural key mapped value of first item
    assertEquals(6, stream.readShort()); // length of first item
    assertEquals(3, stream.readByte()); // plural key mapped value of second item
    assertEquals(6, stream.readShort()); // length of second item
    assertEquals(4, stream.readByte()); // plural key mapped value of third item
    assertEquals(7, stream.readShort()); // length of third item

    assertEquals(1, stream.readByte()); // ordinal of the gender enum for first string
    assertEquals(3, stream.readByte()); // number of categories.
    assertEquals(1, stream.readByte()); // plural key mapped value of first item
    assertEquals(9, stream.readShort()); // length of first item
    assertEquals(3, stream.readByte()); // plural key mapped value of second item
    assertEquals(9, stream.readShort()); // length of second item
    assertEquals(4, stream.readByte()); // plural key mapped value of third item
    assertEquals(10, stream.readShort()); // length of third item

    assertEquals(2, stream.readByte()); // ordinal of the gender enum for first plural
    assertEquals(3, stream.readByte()); // number of categories.
    assertEquals(1, stream.readByte()); // plural key mapped value of first item
    assertEquals(9, stream.readShort()); // length of first item
    assertEquals(3, stream.readByte()); // plural key mapped value of second item
    assertEquals(9, stream.readShort()); // length of second item
    assertEquals(4, stream.readByte()); // plural key mapped value of third item
    assertEquals(10, stream.readShort()); // length of third item

    assertEquals(3, stream.readShort()); // delta of id for the second plural
    assertEquals(2, stream.readByte()); // number of genders

    assertEquals(0, stream.readByte()); // ordinal of the gender enum for first plural
    assertEquals(3, stream.readByte()); // number of categories.
    assertEquals(0, stream.readByte()); // plural key mapped value of first item
    assertEquals(7, stream.readShort()); // length of first item
    assertEquals(2, stream.readByte()); // plural key mapped value of second item
    assertEquals(6, stream.readShort()); // length of second item
    assertEquals(5, stream.readByte()); // plural key mapped value of third item
    assertEquals(8, stream.readShort()); // length of third item

    assertEquals(1, stream.readByte()); // ordinal of the gender enum for first plural
    assertEquals(3, stream.readByte()); // number of categories.
    assertEquals(0, stream.readByte()); // plural key mapped value of first item
    assertEquals(10, stream.readShort()); // length of first item
    assertEquals(2, stream.readByte()); // plural key mapped value of second item
    assertEquals(9, stream.readShort()); // length of second item
    assertEquals(5, stream.readByte()); // plural key mapped value of third item
    assertEquals(11, stream.readShort()); // length of third item

    // plural strings
    assertEquals("P1_one", readStringOfLength(stream, 6));
    assertEquals("P1_few", readStringOfLength(stream, 6));
    assertEquals("P1_many", readStringOfLength(stream, 7));

    assertEquals("P1_one_f1", readStringOfLength(stream, 9));
    assertEquals("P1_few_f1", readStringOfLength(stream, 9));
    assertEquals("P1_many_f1", readStringOfLength(stream, 10));

    assertEquals("P1_one_m2", readStringOfLength(stream, 9));
    assertEquals("P1_few_m2", readStringOfLength(stream, 9));
    assertEquals("P1_many_m2", readStringOfLength(stream, 10));

    assertEquals("P2_zero", readStringOfLength(stream, 7));
    assertEquals("P2_two", readStringOfLength(stream, 6));
    assertEquals("P2_other", readStringOfLength(stream, 8));

    assertEquals("P2_zero_f1", readStringOfLength(stream, 10));
    assertEquals("P2_two_f1", readStringOfLength(stream, 9));
    assertEquals("P2_other_f1", readStringOfLength(stream, 11));

    // Arrays
    assertEquals(2, stream.readInt()); // number of arrays
    assertEquals(12345694, stream.readInt()); // res Id of the first string

    assertEquals(0, stream.readShort()); // delta of id for first element
    assertEquals(3, stream.readByte()); // number of genders

    assertEquals(0, stream.readByte()); // ordinal of the gender enum for first array
    assertEquals(2, stream.readByte()); // number of array elements.
    assertEquals(6, stream.readShort()); // length of first item
    assertEquals(6, stream.readShort()); // length of second item

    assertEquals(1, stream.readByte()); // ordinal of the gender enum for second array
    assertEquals(2, stream.readByte()); // number of array elements.
    assertEquals(9, stream.readShort()); // length of first item
    assertEquals(9, stream.readShort()); // length of second item

    assertEquals(2, stream.readByte()); // ordinal of the gender enum for third array
    assertEquals(2, stream.readByte()); // number of array elements.
    assertEquals(9, stream.readShort()); // length of first item
    assertEquals(9, stream.readShort()); // length of second item

    assertEquals(5, stream.readShort()); // delta of id for second element
    assertEquals(2, stream.readByte()); // number of genders

    assertEquals(0, stream.readByte()); // ordinal of the gender enum for first array
    assertEquals(1, stream.readByte()); // number of array elements.
    assertEquals(6, stream.readShort()); // length of first item

    assertEquals(2, stream.readByte()); // ordinal of the gender enum for second array
    assertEquals(1, stream.readByte()); // number of array elements.
    assertEquals(9, stream.readShort()); // length of first item

    // array strings
    assertEquals("A1_one", readStringOfLength(stream, 6));
    assertEquals("A1_two", readStringOfLength(stream, 6));
    assertEquals("A1_one_f1", readStringOfLength(stream, 9));
    assertEquals("A1_two_f1", readStringOfLength(stream, 9));
    assertEquals("A1_one_m2", readStringOfLength(stream, 9));
    assertEquals("A1_two_m2", readStringOfLength(stream, 9));
    assertEquals("A2_one", readStringOfLength(stream, 6));
    assertEquals("A2_one_m2", readStringOfLength(stream, 9));
  }

  private String readStringOfLength(DataInputStream stream, int length) throws IOException {
    byte[] data = new byte[length];
    assertEquals(length, stream.read(data, 0, length));
    return new String(data);
  }
}
