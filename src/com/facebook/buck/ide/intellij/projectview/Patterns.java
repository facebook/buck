/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij.projectview;

import com.google.common.base.Joiner;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility code for working with {@link java.util.regex.Pattern regex patterns.} Only
 * package-visible, for now at least, because the APIs are not very general.
 */
class Patterns {
  private final Pattern[] patterns;

  private Patterns(Pattern... patterns) {
    this.patterns = patterns;
  }

  private Patterns(String... patterns) {
    Pattern[] compiled = new Pattern[patterns.length];
    for (int index = 0, length = patterns.length; index < length; ++index) {
      compiled[index] = Pattern.compile(patterns[index]);
    }
    this.patterns = compiled;
  }

  /**
   * Parameter to {@link #onAnyMatch(String, MatchHandler)}. Normally will be implemented as a
   * lambda.
   */
  interface MatchHandler {
    /**
     * Called when {@link #onAnyMatch(String, MatchHandler)} finds a match
     *
     * @param matcher The {@link Matcher} with match location, captures, &c
     * @param text The text that was passed to {@link #onMatch(Matcher, String)}
     */
    void onMatch(Matcher matcher, String text);
  }

  /**
   * Passes {@code target} to each regex passed to the constructor. Calls {@code handler} on first
   * match.
   *
   * @param target Text to match against each regex
   * @param handler Code to call on first match
   * @return {@code true} if any regex matched; {@code false} if no regex matched
   */
  boolean onAnyMatch(String target, MatchHandler handler) {
    for (Pattern pattern : patterns) {
      Matcher match = pattern.matcher(target);
      if (match.find()) {
        handler.onMatch(match, target);
        return true;
      }
    }
    return false;
  }

  /**
   * The {@link Patterns} constructor takes either a {@code String...} parameter or a {@code
   * Pattern...} parameter. This {@code Builder} is an alternative to {@code new Patterns(compile(a,
   * b, c), compile(d, e, f))}
   */
  static Builder builder() {
    return new Builder();
  }

  static class Builder {
    private final List<String> patterns = new ArrayList<>();

    /** Add a regex to the Builder */
    Builder add(String pattern) {
      patterns.add(pattern);
      return this;
    }

    /** Add a regex to the Builder */
    Builder add(String... elements) {
      return add(join(elements));
    }

    /** Create a new {@#link Patterns} */
    Patterns build() {
      String[] patternArray = new String[patterns.size()];
      return new Patterns(patterns.toArray(patternArray));
    }
  }

  /**
   * Factory method to create a single-regex {@link Patterns}: equivalent to {@code
   * Patterns.builder().add(pattern).build()} but smaller and more efficient
   */
  static Patterns build(String pattern) {
    return new Patterns(pattern);
  }

  /**
   * Factory method to create a single-regex {@link Patterns}: equivalent to {@code
   * Patterns.builder().add(elements).build()} but smaller and more efficient
   */
  static Patterns build(String... elements) {
    return new Patterns(join(elements));
  }

  /**
   * Convenience function to compile a complex regex from a comma-delimited stream of elements. This
   * can be easier to read than a single long string or {@code "..." + "..."}.
   *
   * @param elements A stream of regex elements
   * @return A compiled regex
   */
  static Pattern compile(String... elements) {
    return compile(0, elements);
  }

  /**
   * Convenience function to compile a complex regex from a comma-delimited stream of elements. This
   * can be easier to read than a single long string or {@code "..." + "..."}.
   *
   * @param flags {@link Pattern#compile(String, int) Pattern.compile() flags}
   * @param elements A stream of regex elements
   * @return A compiled regex
   */
  static Pattern compile(int flags, String... elements) {
    return Pattern.compile(join(elements), flags);
  }

  /**
   * Convenience function to generate a capture group. {@code "capture("foo")} is bigger (and more
   * expensive) than {@code "(foo)"} but it's very explicit, and contrasts naturally with {@link
   * #noncapture(String)}.
   */
  static String capture(String element) {
    return "(" + element + ")";
  }

  /**
   * Convenience function to generate a capture group. {@code "capture("foo")} is bigger (and more
   * expensive) than {@code "(foo)"} but it's very explicit, and contrasts naturally with {@link
   * #noncapture(String)}.
   */
  static String capture(String... elements) {
    return capture(join(elements));
  }

  /**
   * Convenience function to generate a non-capture group. {@code "noncapture("foo"} is easier to
   * read than {@code "(?:foo)"}.
   */
  static String noncapture(String element) {
    return "(?:" + element + ")";
  }

  /**
   * Convenience function to generate a non-capture group. {@code "noncapture("foo"} is easier to
   * read than {@code "(?:foo)"}.
   */
  static String noncapture(String... elements) {
    return noncapture(join(elements));
  }

  /**
   * Convenience function to generate an optional regex element. {@code optional(noncapture("foo"))}
   * is a lot easier to read/verify than {@code "(?:foo)?"}.
   */
  static String optional(String element) {
    return element + "?";
  }

  /**
   * Convenience function to generate an optional regex element. {@code optional(noncapture("foo"))}
   * is a lot easier to read/verify than {@code "(?:foo)?"}.
   */
  static String optional(String... elements) {
    return optional(join(elements));
  }

  private static String join(String... elements) {
    return Joiner.on("").join(elements);
  }
}
