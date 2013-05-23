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

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Compares version strings such as "4.2.2" and "17.0".
 */
public class VersionStringComparator implements Comparator<String> {

  private static final Pattern VERSION_STRING_PATTERN = Pattern.compile("(\\d+)(\\.\\d+)*?");

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

    String[] partsA = a.split("\\.");
    String[] partsB = b.split("\\.");

    for (int i = 0; i < partsA.length; i++) {
      if (i >= partsB.length) {
        return 1;
      }

      String partA = partsA[i];
      String partB = partsB[i];

      int valueA = Integer.parseInt(partA);
      int valueB = Integer.parseInt(partB);
      int delta = valueA - valueB;
      if (delta != 0) {
        return delta;
      }
    }

    if (partsA.length == partsB.length) {
      return 0;
    } else {
      return -1;
    }
  }
}
