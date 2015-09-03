/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.network;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;

/**
 * Use this formatter as you would use a Builder to create Hive formatted rows that will transmit
 * correctly
 * according to Scribe/Hive protocol.
 */
public final class HiveRowFormatter {
  private static final String COLUMN_SEPARATOR = "\001";

  /**
   * In reality scribe/hive can encode further nested levels (array<array<string>>) by increasing
   * the ASCII value all the way up to \010. In some codebases, that seems to be the limit.
   * However, for our purpose here we will keep it simple with just one level.
   */
  private static final String ARRAY_SEPARATOR = "\002";

  private static final Function<Object, String> ESCAPE_FUNCTION = new Function<Object, String>() {
    @Override
    public String apply(Object input) {
      if (input == null) {
        return "";
      }

      return escapeHiveString(input.toString());
    }
  };

  private final StringBuilder row;

  private HiveRowFormatter() {
    row = new StringBuilder();
  }

  public static HiveRowFormatter newFormatter() {
    return new HiveRowFormatter();
  }

  public HiveRowFormatter appendLong(long value) {
    return appendString(value);
  }

  public HiveRowFormatter appendBoolean(boolean requestSuccessful) {
    return appendString(requestSuccessful);
  }

  public <T> HiveRowFormatter appendString(T value) {
    if (row.length() > 0) {
      row.append(COLUMN_SEPARATOR);
    }

    row.append(escapeHiveString(value.toString()));
    return this;
  }

  public <T> HiveRowFormatter appendStringIterable(Iterable<T> valueArray) {
    if (row.length() > 0) {
      row.append(COLUMN_SEPARATOR);
    }

    Iterable<String> escapedValues = Iterables.transform(valueArray, ESCAPE_FUNCTION);
    row.append(Joiner.on(ARRAY_SEPARATOR).join(escapedValues));
    return this;
  }

  public String build() {
    return toString();
  }

  @Override
  public String toString() {
    return row.toString();
  }

  private static String escapeHiveString(String unescaped) {
    // Hive-escape in a way the original string is reversible if needed:
    //   \001 = column divider
    //   \r\n = rows divider
    //   \\ = needs to be escaped to be able to reverse back into the original string
    return unescaped
        .replace("\\", "\\\\")
        .replace(COLUMN_SEPARATOR, "\\001")
        .replace(ARRAY_SEPARATOR, "\\002")
        .replace("\n", "\\n")
        .replace("\r", "\\r");

  }
}
