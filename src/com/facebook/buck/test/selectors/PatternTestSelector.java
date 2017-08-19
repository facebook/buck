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

package com.facebook.buck.test.selectors;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A {@link TestDescription} will match if this selector's class-part is a substring of the {@link
 * TestDescription}'s full class-name, or if this selector's class-name, when interpreted as a
 * java.util.regex regular-expression, matches the {@link TestDescription}'s full class-name.
 *
 * <p>(The same rules apply for the method-name as well. If this selector's class-part or
 * method-part are null, all class-names or method-names will match.)
 */
public class PatternTestSelector implements TestSelector {

  static final PatternTestSelector INCLUDE_EVERYTHING = new PatternTestSelector(true, null, null);
  static final PatternTestSelector EXCLUDE_EVERYTHING = new PatternTestSelector(false, null, null);

  private final boolean inclusive;
  @Nullable private final Pattern classPattern;
  @Nullable private final Pattern methodPattern;

  private PatternTestSelector(
      boolean inclusive, @Nullable Pattern classPattern, @Nullable Pattern methodPattern) {
    this.inclusive = inclusive;
    this.classPattern = classPattern;
    this.methodPattern = methodPattern;
  }

  /**
   * Build a {@link PatternTestSelector} from the given String. Selector strings should be of the
   * form "[is-exclusive][class-part]#[method-part]". If "[is-exclusive]" is a "!" then this
   * selector will exclude tests, otherwise it will include tests.
   *
   * <p>If the class-part (or method-part) are omitted, then all classes or methods will match.
   * Consequently "#" means "include everything" and "!#" means "exclude everything".
   *
   * <p>If the selector string doesn't contain a "#" at all, it is interpreted as a class-part.
   *
   * @param rawSelectorString An unparsed selector string.
   */
  public static TestSelector buildFromSelectorString(String rawSelectorString) {
    if (rawSelectorString == null || rawSelectorString.isEmpty()) {
      throw new RuntimeException("Cannot build from a null or empty string!");
    }

    boolean isInclusive = true;
    String remainder;
    if (rawSelectorString.charAt(0) == '!') {
      isInclusive = false;
      remainder = rawSelectorString.substring(1);
    } else {
      remainder = rawSelectorString;
    }
    // Reuse universal inclusion
    if (remainder.equals("#")) {
      return isInclusive ? INCLUDE_EVERYTHING : EXCLUDE_EVERYTHING;
    }

    Pattern classPattern;
    Pattern methodPattern = null;
    String[] parts = remainder.split("#", -1);

    try {
      switch (parts.length) {
          // "com.example.Test", "com.example.Test#"
        case 1:
          classPattern = getPatternOrNull(parts[0]);
          break;

          // "com.example.Test#testX", "#testX", "#"
        case 2:
          classPattern = getPatternOrNull(parts[0]);
          methodPattern = getPatternOrNull(parts[1]);
          break;

          // Invalid string, like "##"
        default:
          throw new TestSelectorParseException(
              String.format("Test selector '%s' contains more than one '#'!", rawSelectorString));
      }
    } catch (PatternSyntaxException e) {
      throw new TestSelectorParseException(
          String.format("Regular expression error in '%s': %s", rawSelectorString, e.getMessage()));
    }

    return new PatternTestSelector(isInclusive, classPattern, methodPattern);
  }

  @Override
  public String getRawSelector() {
    StringBuilder builder = new StringBuilder();
    if (!inclusive) {
      builder.append('!');
    }
    if (classPattern != null) {
      builder.append(classPattern.toString());
    }
    builder.append('#');
    if (methodPattern != null) {
      builder.append(methodPattern.toString());
    }
    return builder.toString();
  }

  @Nullable
  private static Pattern getPatternOrNull(String string) {
    if (string.isEmpty()) {
      return null;
    } else {
      if (!string.endsWith("$")) {
        string = string + "$";
      }
      return Pattern.compile(string);
    }
  }

  @Override
  public String getExplanation() {
    if (isMatchAnyClass() && isMatchAnyMethod()) {
      return isInclusive() ? "include everything else" : "exclude everything else";
    }
    return String.format(
        "%s class:%s method:%s",
        isInclusive() ? "include" : "exclude",
        isMatchAnyClass() ? "<any>" : classPattern,
        isMatchAnyMethod() ? "<any>" : methodPattern);
  }

  @Override
  public boolean isInclusive() {
    return inclusive;
  }

  @Override
  public boolean isMatchAnyClass() {
    return classPattern == null;
  }

  @Override
  public boolean isMatchAnyMethod() {
    return methodPattern == null;
  }

  @Override
  public boolean matches(TestDescription description) {
    return matchesClassName(description.getClassName())
        && matchesMethodName(description.getMethodName());
  }

  @Override
  public boolean matchesClassName(String className) {
    if (classPattern == null) {
      return true;
    }
    return classPattern.matcher(className).find();
  }

  private boolean matchesMethodName(String methodName) {
    if (methodPattern == null) {
      return true;
    }
    return methodPattern.matcher(methodName).find();
  }
}
