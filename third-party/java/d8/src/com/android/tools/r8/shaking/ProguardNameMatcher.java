// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

public abstract class ProguardNameMatcher {

  private static final ProguardNameMatcher MATCH_ALL_NAMES = new MatchAllNames();

  private ProguardNameMatcher() {

  }

  public static ProguardNameMatcher create(String pattern) {
    if (pattern.equals("*")) {
      return MATCH_ALL_NAMES;
    } else if (pattern.contains("*") || pattern.contains("?")) {
      return new MatchNamePattern(pattern);
    } else {
      return new MatchSpecificName(pattern);
    }
  }

  /**
   * Determines if the proguard name pattern matches the given field or method name.
   *
   * @param pattern Proguard name pattern potentially with wildcards: ?, *.
   * @param name The name to match against.
   * @return true iff the pattern matches the name.
   */
  public static boolean matchFieldOrMethodName(String pattern, String name) {
    return matchFieldOrMethodNameImpl(pattern, 0, name, 0);
  }

  private static boolean matchFieldOrMethodNameImpl(
      String pattern, int patternIndex, String name, int nameIndex) {
    for (int i = patternIndex; i < pattern.length(); i++) {
      char patternChar = pattern.charAt(i);
      switch (patternChar) {
        case '*':
          // Match the rest of the pattern against the rest of the name.
          for (int nextNameIndex = nameIndex; nextNameIndex <= name.length(); nextNameIndex++) {
            if (matchFieldOrMethodNameImpl(pattern, i + 1, name, nextNameIndex)) {
              return true;
            }
          }
          return false;
        case '?':
          if (nameIndex == name.length()) {
            return false;
          }
          nameIndex++;
          break;
        default:
          if (nameIndex == name.length() || patternChar != name.charAt(nameIndex++)) {
            return false;
          }
          break;
      }
    }
    return nameIndex == name.length();
  }

  public abstract boolean matches(String name);

  private static class MatchAllNames extends ProguardNameMatcher {

    @Override
    public boolean matches(String name) {
      return true;
    }

    @Override
    public String toString() {
      return "*";
    }
  }

  private static class MatchNamePattern extends ProguardNameMatcher {

    private final String pattern;

    MatchNamePattern(String pattern) {
      this.pattern = pattern;
    }

    @Override
    public boolean matches(String name) {
      return matchFieldOrMethodName(pattern, name);
    }

    @Override
    public String toString() {
      return pattern;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      return o instanceof MatchNamePattern && pattern.equals(((MatchNamePattern) o).pattern);
    }

    @Override
    public int hashCode() {
      return pattern.hashCode();
    }
  }

  private static class MatchSpecificName extends ProguardNameMatcher {

    private final String name;

    MatchSpecificName(String name) {
      this.name = name;
    }

    @Override
    public boolean matches(String name) {
      return this.name.equals(name);
    }

    @Override
    public String toString() {
      return name;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof MatchSpecificName && name.equals(((MatchSpecificName) o).name);
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }
  }
}
