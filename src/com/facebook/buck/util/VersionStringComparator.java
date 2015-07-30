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

import com.google.common.collect.ImmutableList;

import java.util.Comparator;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares version strings such as "4.2.2", "17.0", "r10e-rc4".
 *
 * Comparing different schemas e.g. "r10e-rc4" vs "4.2.2" is undefined.
 */
public class VersionStringComparator implements Comparator<String> {

  private static final Pattern VERSION_STRING_PATTERN = Pattern.compile(
          "^[rR]?(\\d+)[a-zA-Z]*(\\.\\d+)*?([-_]rc\\d+)*?(?:-preview)?$"
  );
  private static final Pattern IGNORED_FIELDS_PATTERN = Pattern.compile("(?:^[rR])|(?:-preview)");
  private static final Pattern DELIMITER_PATTERN = Pattern.compile("\\.|(?:[-_]rc)");
  private static final Pattern NUMBER_ALPHA_PATTERN = Pattern.compile("(\\d+)([a-zA-Z]+)");

  public static boolean isValidVersionString(String str) {
    return VERSION_STRING_PATTERN.matcher(str).matches();
  }

  @Override
  public int compare(String a, String b) {
    if (!isValidVersionString(a)) {
      throw new RuntimeException("Invalid version string: " + a);
    }

    if (!isValidVersionString(b)) {
      throw new RuntimeException("Invalid version string: " + b);
    }

    String cleanedA = IGNORED_FIELDS_PATTERN.matcher(a).replaceAll("");
    String cleanedB = IGNORED_FIELDS_PATTERN.matcher(b).replaceAll("");

    String[] partsA = DELIMITER_PATTERN.split(cleanedA);
    String[] partsB = DELIMITER_PATTERN.split(cleanedB);

    Iterator<Integer> valuesA = partsToValues(partsA).iterator();
    Iterator<Integer> valuesB = partsToValues(partsB).iterator();

    while (valuesA.hasNext()) {
      if (!valuesB.hasNext()) {
        return 1;
      }

      int comp = valuesA.next().compareTo(valuesB.next());
      if (comp != 0) {
        return comp;
      }
    }

    return valuesB.hasNext() ? -1 : 0;
  }

  private ImmutableList<Integer> partsToValues(String[] stringParts) {
    ImmutableList.Builder<Integer> valuesBuilder = new ImmutableList.Builder<>();
    for (String part : stringParts) {
      Matcher matcher = NUMBER_ALPHA_PATTERN.matcher(part);
      if (matcher.matches()) {
        valuesBuilder.add(Integer.parseInt(matcher.group(1)));
        String characters = matcher.group(2);
        int value = 0;
        for (int i = 0; i < characters.length(); i++) {
          value += Math.pow(100, characters.length() - i) * characters.charAt(i);
        }
        valuesBuilder.add(value);
      } else {
        valuesBuilder.add(Integer.parseInt(part));
      }
    }
    return valuesBuilder.build();
  }
}
