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

package com.facebook.buck.android;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/**
 * Filter for internal class names.
 *
 * <p>We use this to determine if a class must be placed in our primary dex.
 * It supports prefix, suffix, substring, and exact matches.
 */
public class ClassNameFilter {
  // We use naive algorithms for prefix, suffix, and substring, but these could easily be
  // optimzied using RE2 or some other more specialized search algorithms.
  private final ImmutableList<String> prefixes;
  private final ImmutableList<String> suffixes;
  private final ImmutableList<String> substrings;
  private final ImmutableSet<String> exactMatches;

  private ClassNameFilter(
      Iterable<String> prefixes,
      Iterable<String> suffixes,
      Iterable<String> substrings,
      Iterable<String> exactMatches) {
    this.prefixes = ImmutableList.copyOf(prefixes);
    this.suffixes = ImmutableList.copyOf(suffixes);
    this.substrings = ImmutableList.copyOf(substrings);
    this.exactMatches = ImmutableSet.copyOf(exactMatches);
  }

  /**
   * Convenience factory to produce a filter from a very simple pattern language.
   *
   * <p>patterns are substrings by default, but {@code ^} at the start or end of a pattern
   * anchors it to the start or end of the class name.
   *
   * @param patterns  Patterns to include in the filter.
   * @return  A new filter.
   */
  public static ClassNameFilter fromConfiguration(Iterable<String> patterns) {
    ImmutableList.Builder<String> prefixes = ImmutableList.builder();
    ImmutableList.Builder<String> suffixes = ImmutableList.builder();
    ImmutableList.Builder<String> substrings = ImmutableList.builder();
    ImmutableSet.Builder<String> exactMatches = ImmutableSet.builder();

    for (String pattern : patterns) {
      boolean isPrefix = pattern.charAt(0) == '^';
      boolean isSuffix = pattern.charAt(pattern.length()-1) == '^';
      if (isPrefix && isSuffix) {
        exactMatches.add(pattern.substring(1, pattern.length() - 1));
      } else if (isPrefix) {
        prefixes.add(pattern.substring(1));
      } else if (isSuffix) {
        suffixes.add(pattern.substring(0, pattern.length()-1));
      } else {
        substrings.add(pattern);
      }
    }

    return new ClassNameFilter(
        prefixes.build(),
        suffixes.build(),
        substrings.build(),
        exactMatches.build());
  }

  public boolean matches(String internalClassName) {
    if (exactMatches.contains(internalClassName)) {
      return true;
    }

    for (String prefix : prefixes) {
      if (internalClassName.startsWith(prefix)) {
        return true;
      }
    }

    for (String suffix : suffixes) {
      if (internalClassName.endsWith(suffix)) {
        return true;
      }
    }

    for (String substring : substrings) {
      if (internalClassName.contains(substring)) {
        return true;
      }
    }

    return false;
  }
}
