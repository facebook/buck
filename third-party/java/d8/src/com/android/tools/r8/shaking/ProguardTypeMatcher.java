// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.utils.DescriptorUtils;

public abstract class ProguardTypeMatcher {

  private static final String MATCH_ALL_PATTERN = "***";
  private static final String MATCH_ANY_ARG_SEQUENCE_PATTERN = "...";
  private static final String LEGACY_MATCH_CLASS_PATTERN = "*";
  private static final String MATCH_CLASS_PATTERN = "**";
  private static final String MATCH_BASIC_PATTERN = "%";

  private ProguardTypeMatcher() {
  }

  enum ClassOrType {
    CLASS,
    TYPE
  }

  public abstract boolean matches(DexType type);

  @Override
  public abstract String toString();

  public boolean isTripleDotPattern() {
    return false;
  }

  public static ProguardTypeMatcher create(String pattern, ClassOrType kind,
      DexItemFactory dexItemFactory) {
    if (pattern == null) {
      return null;
    }
    switch (pattern) {
      case MATCH_ALL_PATTERN:
        return MatchAllTypes.MATCH_ALL_TYPES;
      case MATCH_ANY_ARG_SEQUENCE_PATTERN:
        return MatchAnyArgSequence.MATCH_ANY_ARG_SEQUENCE;
      case MATCH_CLASS_PATTERN:
        return MatchClassTypes.MATCH_CLASS_TYPES;
      case LEGACY_MATCH_CLASS_PATTERN:
        return MatchClassTypes.LEGACY_MATCH_CLASS_TYPES;
      case MATCH_BASIC_PATTERN:
        return MatchBasicTypes.MATCH_BASIC_TYPES;
      default:
        if (!pattern.contains("*") && !pattern.contains("%") && !pattern.contains("?")) {
          return new MatchSpecificType(
              dexItemFactory.createType(DescriptorUtils.javaTypeToDescriptor(pattern)));
        }
        return new MatchTypePattern(pattern, kind);
    }
  }

  public static ProguardTypeMatcher defaultAllMatcher() {
    return MatchAllTypes.MATCH_ALL_TYPES;
  }

  @Override
  public abstract boolean equals(Object o);

  @Override
  public abstract int hashCode();

  public DexType getSpecificType() {
    return null;
  }

  private static class MatchAllTypes extends ProguardTypeMatcher {

    private static final ProguardTypeMatcher MATCH_ALL_TYPES = new MatchAllTypes();

    @Override
    public boolean matches(DexType type) {
      return true;
    }

    @Override
    public String toString() {
      return MATCH_ALL_PATTERN;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof MatchAllTypes;
    }

    @Override
    public int hashCode() {
      return getClass().hashCode();
    }
  }

  private static class MatchAnyArgSequence extends ProguardTypeMatcher {

    private static final ProguardTypeMatcher MATCH_ANY_ARG_SEQUENCE = new MatchAnyArgSequence();

    @Override
    public boolean matches(DexType type) {
      throw new IllegalStateException();
    }

    @Override
    public String toString() {
      return MATCH_ANY_ARG_SEQUENCE_PATTERN;
    }

    @Override
    public boolean isTripleDotPattern() {
      return true;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof MatchAnyArgSequence;
    }

    @Override
    public int hashCode() {
      return getClass().hashCode();
    }
  }

  private static class MatchClassTypes extends ProguardTypeMatcher {

    private static final ProguardTypeMatcher MATCH_CLASS_TYPES = new MatchClassTypes(
        MATCH_CLASS_PATTERN);
    private static final ProguardTypeMatcher LEGACY_MATCH_CLASS_TYPES = new MatchClassTypes(
        LEGACY_MATCH_CLASS_PATTERN);

    private final String pattern;

    private MatchClassTypes(String pattern) {
      assert pattern.equals(LEGACY_MATCH_CLASS_PATTERN) || pattern.equals(MATCH_CLASS_PATTERN);
      this.pattern = pattern;
    }

    @Override
    public boolean matches(DexType type) {
      return type.isClassType();
    }

    @Override
    public String toString() {
      return pattern;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof MatchClassTypes && pattern.equals(((MatchClassTypes) o).pattern);
    }

    @Override
    public int hashCode() {
      return pattern.hashCode();
    }
  }

  private static class MatchBasicTypes extends ProguardTypeMatcher {

    private static final ProguardTypeMatcher MATCH_BASIC_TYPES = new MatchBasicTypes();

    @Override
    public boolean matches(DexType type) {
      return type.isPrimitiveType();
    }

    @Override
    public String toString() {
      return MATCH_BASIC_PATTERN;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof MatchBasicTypes;
    }

    @Override
    public int hashCode() {
      return getClass().hashCode();
    }
  }

  public static class MatchSpecificType extends ProguardTypeMatcher {

    public final DexType type;

    private MatchSpecificType(DexType type) {
      this.type = type;
    }

    @Override
    public boolean matches(DexType type) {
      return this.type == type;
    }

    @Override
    public String toString() {
      return type.toSourceString();
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof MatchSpecificType) {
        return type.equals(((MatchSpecificType) o).type);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return type.hashCode();
    }

    @Override
    public DexType getSpecificType() {
      return type;
    }
  }

  private static class MatchTypePattern extends ProguardTypeMatcher {

    private final String pattern;
    private final ClassOrType kind;

    private MatchTypePattern(String pattern, ClassOrType kind) {
      this.pattern = pattern;
      this.kind = kind;
    }

    @Override
    public boolean matches(DexType type) {
      // TODO(herhut): Translate pattern to work on descriptors instead.
      String typeName = type.toSourceString();
      return matchClassOrTypeNameImpl(pattern, 0, typeName, 0, kind);
    }

    private static boolean matchClassOrTypeNameImpl(
        String pattern, int patternIndex, String className, int nameIndex, ClassOrType kind) {
      for (int i = patternIndex; i < pattern.length(); i++) {
        char patternChar = pattern.charAt(i);
        switch (patternChar) {
          case '*':
            boolean includeSeparators = pattern.length() > (i + 1) && pattern.charAt(i + 1) == '*';
            int nextPatternIndex = i + (includeSeparators ? 2 : 1);
            // Fast cases for the common case where a pattern ends with '**' or '*'.
            if (nextPatternIndex == pattern.length()) {
              if (includeSeparators) {
                return kind == ClassOrType.CLASS || !isArrayType(className);
              }
              boolean hasSeparators = containsSeparatorsStartingAt(className, nameIndex);
              return !hasSeparators && (kind == ClassOrType.CLASS || !isArrayType(className));
            }
            // Match the rest of the pattern against the (non-empty) rest of the class name.
            for (int nextNameIndex = nameIndex; nextNameIndex < className.length();
                nextNameIndex++) {
              if (!includeSeparators && className.charAt(nextNameIndex) == '.') {
                return matchClassOrTypeNameImpl(pattern, nextPatternIndex, className, nextNameIndex,
                    kind);
              }
              if (kind == ClassOrType.TYPE && className.charAt(nextNameIndex) == '[') {
                return matchClassOrTypeNameImpl(pattern, nextPatternIndex, className, nextNameIndex,
                    kind);
              }
              if (matchClassOrTypeNameImpl(pattern, nextPatternIndex, className, nextNameIndex,
                  kind)) {
                return true;
              }
            }
            // Finally, check the case where the '*' or '**' eats all of the class name.
            return matchClassOrTypeNameImpl(pattern, nextPatternIndex, className,
                className.length(),
                kind);
          case '?':
            if (nameIndex == className.length() || className.charAt(nameIndex++) == '.') {
              return false;
            }
            break;
          default:
            if (nameIndex == className.length() || patternChar != className.charAt(nameIndex++)) {
              return false;
            }
            break;
        }
      }
      return nameIndex == className.length();
    }

    private static boolean containsSeparatorsStartingAt(String className, int nameIndex) {
      return className.indexOf('.', nameIndex) != -1;
    }

    private static boolean isArrayType(String type) {
      int length = type.length();
      if (length < 2) {
        return false;
      }
      return type.charAt(length - 1) == ']' && type.charAt(length - 2) == '[';
    }

    @Override
    public String toString() {
      return pattern;
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof MatchTypePattern) {
        MatchTypePattern that = (MatchTypePattern) o;
        return kind.equals(that.kind) && pattern.equals(that.pattern);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return pattern.hashCode() * 7 + kind.hashCode();
    }
  }
}
