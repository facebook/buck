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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * Represents string resources of types string, plural and array for a locale. Also responsible
 * for generating a custom format binary file for the resources.
 */
public class StringResources {

  /**
   * Bump this whenever there's a change in the file format. The parser can decide to abort parsing
   * if the version it finds in the file does not match it's own version, thereby avoiding
   * potential data corruption issues.
   */
  private static final int FORMAT_VERSION = 1;

  public final SortedMap<Integer, String> strings;
  public final SortedMap<Integer, ImmutableMap<String, String>> plurals;
  // This is not a TreeMultimap because we only want the keys to be sorted by their natural
  // ordering, not the values array. The values should be in the same order as insertion.
  public final SortedMap<Integer, ImmutableList<String>> arrays;

  /**
   * These are the 6 fixed plural categories for string resources in Android. This mapping is not
   * expected to change over time. We encode them as integers to optimize space.
   *
   * <p>For more information, refer to:
   * <a href="http://developer.android.com/guide/topics/resources/string-resource.html#Plurals">
   *   String Resources | Android Developers
   * </a></p>
   */
  private static final ImmutableMap<String, Integer> PLURAL_CATEGORY_MAP =
      ImmutableMap.<String, Integer>builder()
          .put("zero", 0)
          .put("one", 1)
          .put("two", 2)
          .put("few", 3)
          .put("many", 4)
          .put("other", 5)
          .build();

  private static Charset charset = Charsets.UTF_8;

  public StringResources(
      SortedMap<Integer, String> strings,
      SortedMap<Integer, ImmutableMap<String, String>> plurals,
      SortedMap<Integer, ImmutableList<String>> arrays) {
    this.strings = strings;
    this.plurals = plurals;
    this.arrays = arrays;
  }

  public StringResources getMergedResources(StringResources otherResources) {
    TreeMap<Integer, String> stringsMap = Maps.newTreeMap(otherResources.strings);
    TreeMap<Integer, ImmutableMap<String, String>> pluralsMap =
        Maps.newTreeMap(otherResources.plurals);
    TreeMap<Integer, ImmutableList<String>> arraysMap = Maps.newTreeMap(otherResources.arrays);

    stringsMap.putAll(strings);
    pluralsMap.putAll(plurals);
    arraysMap.putAll(arrays);

    return new StringResources(stringsMap, pluralsMap, arraysMap);
  }

  /**
   * Returns a byte array that represents the entire set of strings, plurals and string arrays in
   * the following binary file format:
   * <p>
   * <pre>
   *   [Int: Version]
   *   [Int: # of strings]
   *   [Int: Smallest resource id among strings]
   *   [Short: resource id delta][Short: length of the string] x # of strings
   *   [Byte array of the string value] x # of strings
   *   [Int: # of plurals]
   *   [Int: Smallest resource id among plurals]
   *   [[Short: resource id delta][Byte: #categories][[Byte: category][Short: length of plural
   *   value]] x #categories] x # of plurals
   *   [Byte array of plural value] x Summation of plural categories over # of plurals
   *   [Int: # of arrays]
   *   [Int: Smallest resource id among arrays]
   *   [[Short: resource id delta][Int: #elements][Short: length of element] x #elements] x # of
   *   arrays
   *   [Byte array of string value] x Summation of array elements over # of arrays
   * </pre>
   * </p>
   */
  @Nullable
  public byte[] getBinaryFileContent() {
    try (
      ByteArrayOutputStream bytesStream = new ByteArrayOutputStream();
      DataOutputStream outputStream = new DataOutputStream(bytesStream)
    ) {
      outputStream.writeInt(FORMAT_VERSION);

      writeStrings(outputStream);
      writePlurals(outputStream);
      writeArrays(outputStream);

      return bytesStream.toByteArray();
    } catch (IOException e) {
      return null;
    }
  }

  private void writeStrings(DataOutputStream outputStream) throws IOException {
    outputStream.writeInt(strings.size());
    if (strings.isEmpty()) {
      return;
    }
    int previousResourceId = strings.firstKey();
    outputStream.writeInt(previousResourceId);

    try (ByteArrayOutputStream dataStream = new ByteArrayOutputStream()) {
      for (Map.Entry<Integer, String> entry : strings.entrySet()) {
        byte[] resourceBytes = getUnescapedStringBytes(entry.getValue());
        writeShort(outputStream, entry.getKey() - previousResourceId);
        writeShort(outputStream, resourceBytes.length);
        dataStream.write(resourceBytes, 0, resourceBytes.length);

        previousResourceId = entry.getKey();
      }
      outputStream.write(dataStream.toByteArray());
    }
  }

  private void writePlurals(DataOutputStream outputStream) throws IOException {
    outputStream.writeInt(plurals.size());
    if (plurals.isEmpty()) {
      return;
    }
    int previousResourceId = plurals.firstKey();
    outputStream.writeInt(previousResourceId);

    try (ByteArrayOutputStream dataStream = new ByteArrayOutputStream()) {
      for (Map.Entry<Integer, ImmutableMap<String, String>> entry : plurals.entrySet()) {
        writeShort(outputStream, entry.getKey() - previousResourceId);
        ImmutableMap<String, String> categoryMap = entry.getValue();
        outputStream.writeByte(categoryMap.size());

        for (Map.Entry<String, String> cat : categoryMap.entrySet()) {
          outputStream.writeByte(
              Preconditions.checkNotNull(
                  PLURAL_CATEGORY_MAP.get(cat.getKey()))
                  .byteValue());
          byte[] pluralValue = getUnescapedStringBytes(cat.getValue());
          writeShort(outputStream, pluralValue.length);
          dataStream.write(pluralValue);
        }

        previousResourceId = entry.getKey();
      }

      outputStream.write(dataStream.toByteArray());
    }
  }

  private void writeArrays(DataOutputStream outputStream) throws IOException {
    outputStream.writeInt(arrays.keySet().size());
    if (arrays.keySet().isEmpty()) {
      return;
    }
    int previousResourceId = arrays.firstKey();
    outputStream.writeInt(previousResourceId);
    try (ByteArrayOutputStream dataStream = new ByteArrayOutputStream()) {
      for (int resourceId : arrays.keySet()) {
        writeShort(outputStream, resourceId - previousResourceId);
        ImmutableList<String> arrayValues = arrays.get(resourceId);
        outputStream.writeInt(arrayValues.size());

        for (String arrayValue : arrayValues) {
          byte[] byteValue = getUnescapedStringBytes(arrayValue);
          writeShort(outputStream, byteValue.length);
          dataStream.write(byteValue);
        }

        previousResourceId = resourceId;
      }
      outputStream.write(dataStream.toByteArray());
    }
  }

  private void writeShort(DataOutputStream stream, int number) throws IOException {
    Preconditions.checkState(number <= Short.MAX_VALUE,
        "Error attempting to compact a numeral to short: " + number);
    stream.writeShort(number);
  }

  @VisibleForTesting
  static byte[] getUnescapedStringBytes(String value) {
    return value.replace("\\\"", "\"").replace("\\'", "'").getBytes(charset);
  }
}
