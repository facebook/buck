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

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;

import java.util.Arrays;

import javax.annotation.Nullable;

public final class MoreStrings {

  public static final Predicate<String> NON_EMPTY =
      new Predicate<String>() {
        @Override
        public boolean apply(@Nullable String input) {
          return !Strings.isNullOrEmpty(input);
        }
      };

  /** Utility class: do not instantiate. */
  private MoreStrings() {}

  public static final boolean isEmpty(CharSequence sequence) {
    return sequence.length() == 0;
  }

  public static String withoutSuffix(String str, String suffix) {
    Preconditions.checkArgument(str.endsWith(suffix), "%s must end with %s", str, suffix);
    return str.substring(0, str.length() - suffix.length());
  }

  public static String capitalize(String str) {
    Preconditions.checkNotNull(str);
    if (!str.isEmpty()) {
      return str.substring(0, 1).toUpperCase() + str.substring(1);
    } else {
      return "";
    }
  }

  public static int getLevenshteinDistance(String str1, String str2) {
      Preconditions.checkNotNull(str1);
      Preconditions.checkNotNull(str2);

      char[] arr1 = str1.toCharArray();
      char[] arr2 = str2.toCharArray();
      int[][] levenshteinDist = new int [arr1.length + 1][arr2.length + 1];

      for (int i = 0; i <= arr1.length; i++) {
        levenshteinDist[i][0] = i;
      }

      for (int j = 1; j <= arr2.length; j++) {
        levenshteinDist[0][j] = j;
      }

      for (int i = 1; i <= arr1.length; i++) {
        for (int j = 1; j <= arr2.length; j++) {
          if (arr1[i - 1] == arr2[j - 1]) {
            levenshteinDist[i][j] = levenshteinDist[i - 1][j - 1];
          } else {
            levenshteinDist[i][j] =
              Math.min(levenshteinDist[i - 1][j] + 1,
                  Math.min(levenshteinDist[i][j - 1] + 1, levenshteinDist[i - 1][j - 1] + 1));
          }
        }
      }

      return levenshteinDist[arr1.length][arr2.length];
    }

  public static String regexPatternForAny(String... values) {
    return regexPatternForAny(Arrays.asList(values));
  }

  public static String regexPatternForAny(Iterable<String> values) {
    return "((?:" + Joiner.on(")|(?:").join(values) + "))";
  }
}
