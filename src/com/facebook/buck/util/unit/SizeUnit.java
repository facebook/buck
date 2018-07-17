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

package com.facebook.buck.util.unit;

import com.facebook.buck.util.string.MoreStrings;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableMap;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum SizeUnit {
  BYTES(0, "bytes"),
  KILOBYTES(1, "Kbytes"),
  MEGABYTES(2, "Mbytes"),
  GIGABYTES(3, "Gbytes"),
  TERABYTES(4, "Tbytes");

  private final int ordinal;
  private final String abbreviation;

  SizeUnit(int ordinal, String abbreviation) {
    this.ordinal = ordinal;
    this.abbreviation = abbreviation;
  }

  public int getOrdinal() {
    return ordinal;
  }

  public String getAbbreviation() {
    return abbreviation;
  }

  private static long multiplyByByteOrderOfMagnitude(double size, int magnitude) {
    if (magnitude == 0) {
      return (long) size;
    } else if (magnitude > 0) {
      return BigDecimal.valueOf(size).multiply(BigDecimal.valueOf(1024).pow(magnitude)).longValue();
    } else {
      return BigDecimal.valueOf(size)
          .divide(BigDecimal.valueOf(1024).pow(-1 * magnitude))
          .longValue();
    }
  }

  private static final ImmutableMap<String, SizeUnit> SHORT_TO_CODE =
      ImmutableMap.<String, SizeUnit>builder()
          .put("b", BYTES)
          .put("kb", KILOBYTES)
          .put("kilobytes", KILOBYTES)
          .put("mb", MEGABYTES)
          .put("megabytes", MEGABYTES)
          .put("gb", GIGABYTES)
          .put("gigabytes", GIGABYTES)
          .put("tb", TERABYTES)
          .put("terabytes", TERABYTES)
          .build();

  private static final Pattern SIZE_PATTERN =
      Pattern.compile(
          "([\\d]+(?:\\.[\\d]+)?)\\s*" + MoreStrings.regexPatternForAny(SHORT_TO_CODE.keySet()),
          Pattern.CASE_INSENSITIVE);

  /** Parses a string that represents a size into the number of bytes represented by that string. */
  public static long parseBytes(String input) throws NumberFormatException {
    Matcher matcher = SIZE_PATTERN.matcher(input);
    if (matcher.find()) {
      String number = matcher.group(1);
      SizeUnit sizeUnit = SHORT_TO_CODE.get(matcher.group(2).toLowerCase());
      if (sizeUnit != null) {
        try {
          double value = Double.parseDouble(number);
          return sizeUnit.toBytes(value);
        } catch (NumberFormatException e) {
          // If the number was so large as to overflow Long.MAX_VALUE, return LONG.MAX_VALUE.
          return Long.MAX_VALUE;
        }
      }
    }
    throw new NumberFormatException(String.format("%s is not a valid file size", input));
  }

  public long toBytes(double size) {
    return multiplyByByteOrderOfMagnitude(size, getOrdinal() - BYTES.getOrdinal());
  }

  public long toKilobytes(double size) {
    return multiplyByByteOrderOfMagnitude(size, getOrdinal() - KILOBYTES.getOrdinal());
  }

  public long toMegabytes(double size) {
    return multiplyByByteOrderOfMagnitude(size, getOrdinal() - MEGABYTES.getOrdinal());
  }

  public long toGigabytes(double size) {
    return multiplyByByteOrderOfMagnitude(size, getOrdinal() - GIGABYTES.getOrdinal());
  }

  public long toTerabytes(double size) {
    return multiplyByByteOrderOfMagnitude(size, getOrdinal() - TERABYTES.getOrdinal());
  }

  public static Pair<Double, SizeUnit> getHumanReadableSize(double size, SizeUnit unit) {
    if (size == 0) {
      return new Pair<>(0.0, unit);
    }
    int ordinal = unit.getOrdinal();
    double resultSize = size;
    if (size > 1) {
      while (size > 1 && ordinal < 4) {
        size = size / 1024;
        if (size > 1) {
          ordinal++;
          resultSize = size;
        }
      }
    } else {
      while (size < 1024 && ordinal > 0) {
        size = size * 1024;
        if (size < 1024) {
          ordinal--;
          resultSize = size;
        }
      }
    }
    return new Pair<>(resultSize, SizeUnit.values()[ordinal]);
  }

  public static String toHumanReadableString(Pair<Double, SizeUnit> size, Locale locale) {
    return String.format(locale, "%.2f %s", size.getFirst(), size.getSecond().getAbbreviation());
  }
}
