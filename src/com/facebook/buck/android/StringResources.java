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

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.TreeMultimap;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Represents string resources of types string, plural and array for a locale. Also responsible
 * for generating a custom format binary file for the resources.
 */
public class StringResources {
  public final TreeMap<Integer, String> strings;
  public final TreeMap<Integer, ImmutableMap<String, String>> plurals;
  public final TreeMultimap<Integer, String> arrays;

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
      TreeMap<Integer, String> strings,
      TreeMap<Integer, ImmutableMap<String, String>> plurals,
      TreeMultimap<Integer, String> arrays) {
    this.strings = Preconditions.checkNotNull(strings);
    this.plurals = Preconditions.checkNotNull(plurals);
    this.arrays = Preconditions.checkNotNull(arrays);
  }

  public StringResources getMergedResources(StringResources otherResources) {
    TreeMap<Integer, String> stringsMap = Maps.newTreeMap(otherResources.strings);
    TreeMap<Integer, ImmutableMap<String, String>> pluralsMap =
        Maps.newTreeMap(otherResources.plurals);
    TreeMultimap<Integer, String> arraysMap = TreeMultimap.create(otherResources.arrays);

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
   *   [Short: #strings][Short: #plurals][Short: #arrays]
   *   [Int: Smallest resource id among strings]
   *   [Short: resource id offset][Short: length of the string] x #strings
   *   [Int: Smallest resource id among plurals]
   *   [Short: resource id offset][Short: length of string representing the plural] x #plurals
   *   [Int: Smallest resource id among arrays]
   *   [Short: resource id offset][Short: length of string representing the array] x #arrays
   *   [Byte array of the string value] x #strings
   *   [[Byte: #categories][[Byte: category][Short: length of plural][plural]] x #categories] x #plurals
   *   [[Byte: #elements][[Short: length of element][element]] x #elements] x #arrays
   * </pre>
   * </p>
   */
  public byte[] getBinaryFileContent() {
    try (
      ByteArrayOutputStream bos1 = new ByteArrayOutputStream();
      DataOutputStream mapOutStream = new DataOutputStream(bos1);
      ByteArrayOutputStream bos2 = new ByteArrayOutputStream();
      DataOutputStream dataOutStream = new DataOutputStream(bos2)
    ) {
      writeMapsSizes(mapOutStream);

      writeStrings(mapOutStream, dataOutStream);
      writePlurals(mapOutStream, dataOutStream);
      writeArrays(mapOutStream, dataOutStream);

      byte[] result = new byte[bos1.size() + bos2.size()];
      System.arraycopy(bos1.toByteArray(), 0, result, 0, bos1.size());
      System.arraycopy(bos2.toByteArray(), 0, result, bos1.size(), bos2.size());
      return result;
    } catch (IOException e) {
      return null;
    }
  }

  private void writeMapsSizes(DataOutputStream stream) throws IOException {
    stream.writeShort(strings.size());
    stream.writeShort(plurals.size());
    stream.writeShort(arrays.keySet().size());
  }

  private void writeStrings(DataOutputStream mapStream, DataOutputStream dataStream)
      throws IOException {
    if (strings.isEmpty()) {
      return;
    }
    int smallestResourceId = strings.firstKey();
    mapStream.writeInt(smallestResourceId);
    for (Map.Entry<Integer, String> entry : strings.entrySet()) {
      byte[] resourceBytes = entry.getValue().getBytes(charset);
      writeMapEntry(mapStream, entry.getKey() - smallestResourceId, resourceBytes.length);
      dataStream.write(resourceBytes);
    }
  }

  private void writePlurals(DataOutputStream mapStream, DataOutputStream dataStream)
      throws IOException {
    if (plurals.isEmpty()) {
      return;
    }
    int smallestResourceId = plurals.firstKey();
    mapStream.writeInt(smallestResourceId);
    for (Map.Entry<Integer, ImmutableMap<String, String>> entry : plurals.entrySet()) {
      ImmutableMap<String, String> categoryMap = entry.getValue();
      dataStream.writeByte(categoryMap.size());
      int resourceDataLength = 1;

      for (Map.Entry<String, String> cat : categoryMap.entrySet()) {
        dataStream.writeByte(PLURAL_CATEGORY_MAP.get(cat.getKey()).byteValue());
        byte[] pluralValue = cat.getValue().getBytes(charset);
        dataStream.writeShort(pluralValue.length);
        dataStream.write(pluralValue);
        resourceDataLength += 3 + pluralValue.length;
      }

      writeMapEntry(mapStream, entry.getKey() - smallestResourceId, resourceDataLength);
    }
  }

  private void writeArrays(DataOutputStream mapStream, DataOutputStream dataStream)
      throws IOException {
    if (arrays.keySet().isEmpty()) {
      return;
    }
    boolean first = true;
    int smallestResourceId = 0;
    for (int resourceId : arrays.keySet()) {
      if (first) {
        first = false;
        smallestResourceId = resourceId;
        mapStream.writeInt(smallestResourceId);
      }
      Collection<String> arrayValues = arrays.get(resourceId);
      dataStream.writeByte(arrayValues.size());
      int resourceDataLength = 1;

      for (String arrayValue : arrayValues) {
        byte[] byteValue = arrayValue.getBytes(charset);
        dataStream.writeShort(byteValue.length);
        dataStream.write(byteValue);
        resourceDataLength += 2 + byteValue.length;
      }

      writeMapEntry(mapStream, resourceId - smallestResourceId, resourceDataLength);
    }
  }

  private void writeMapEntry(DataOutputStream stream, int resourceOffset, int length)
      throws IOException {
    stream.writeShort(resourceOffset);
    stream.writeShort(length);
  }
}
