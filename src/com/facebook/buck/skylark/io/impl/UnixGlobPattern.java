/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.skylark.io.impl;

import com.facebook.buck.core.path.ForwardRelativePath;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Unix glob pattern (e.g. {@code foo/*.h}). */
public class UnixGlobPattern {
  private final String pattern;
  private final ImmutableList<String> segments;

  private UnixGlobPattern(String pattern, ImmutableList<String> segments) {
    this.pattern = pattern;
    this.segments = segments;
  }

  static boolean isRecursiveSegment(String pattern) {
    return "**".equals(pattern);
  }

  /**
   * Returns whether {@code str} matches the glob pattern {@code pattern}. This method may use the
   * {@code patternCache} to speed up the matching process.
   *
   * @param segmentPattern a glob pattern
   * @param str the string to match
   * @param patternCache a cache from patterns to compiled Pattern objects, or {@code null} to skip
   *     caching
   */
  static boolean segmentMatches(
      String segmentPattern, String str, @Nullable Map<String, Pattern> patternCache) {
    if (segmentPattern.length() == 0 || str.length() == 0) {
      return false;
    }

    // If a filename starts with '.', this char must be matched explicitly.
    if (str.charAt(0) == '.' && segmentPattern.charAt(0) != '.') {
      return false;
    }

    // Common case: **
    if (segmentPattern.equals("**")) {
      return true;
    }

    // Common case: *
    if (segmentPattern.equals("*")) {
      return true;
    }

    // Common case: *.xyz
    if (segmentPattern.charAt(0) == '*' && segmentPattern.lastIndexOf('*') == 0) {
      return str.endsWith(segmentPattern.substring(1));
    }
    // Common case: xyz*
    int lastIndex = segmentPattern.length() - 1;
    // The first clause of this if statement is unnecessary, but is an
    // optimization--charAt runs faster than indexOf.
    if (segmentPattern.charAt(lastIndex) == '*' && segmentPattern.indexOf('*') == lastIndex) {
      return str.startsWith(segmentPattern.substring(0, lastIndex));
    }

    Pattern regex =
        patternCache == null
            ? makeRegexFromWildcard(segmentPattern)
            : patternCache.computeIfAbsent(segmentPattern, UnixGlobPattern::makeRegexFromWildcard);
    return regex.matcher(str).matches();
  }

  /**
   * Returns a regular expression implementing a matcher for "pattern", in which "*" and "?" are
   * wildcards.
   *
   * <p>e.g. "foo*bar?.java" -> "foo.*bar.\\.java"
   */
  private static Pattern makeRegexFromWildcard(String pattern) {
    StringBuilder regexp = new StringBuilder();
    for (int i = 0, len = pattern.length(); i < len; i++) {
      char c = pattern.charAt(i);
      switch (c) {
        case '*':
          int toIncrement = 0;
          if (len > i + 1 && pattern.charAt(i + 1) == '*') {
            // The pattern '**' is interpreted to match 0 or more directory separators, not 1 or
            // more. We skip the next * and then find a trailing/leading '/' and get rid of it.
            toIncrement = 1;
            if (len > i + 2 && pattern.charAt(i + 2) == '/') {
              // We have '**/' -- skip the '/'.
              toIncrement = 2;
            } else if (len == i + 2 && i > 0 && pattern.charAt(i - 1) == '/') {
              // We have '/**' -- remove the '/'.
              regexp.delete(regexp.length() - 1, regexp.length());
            }
          }
          regexp.append(".*");
          i += toIncrement;
          break;
        case '?':
          regexp.append('.');
          break;
          // escape the regexp special characters that are allowed in wildcards
        case '^':
        case '$':
        case '|':
        case '+':
        case '{':
        case '}':
        case '[':
        case ']':
        case '\\':
        case '.':
          regexp.append('\\');
          regexp.append(c);
          break;
        default:
          regexp.append(c);
          break;
      }
    }
    return Pattern.compile(regexp.toString());
  }

  int numRecursivePatterns() {
    int numRecursivePatterns = 0;
    for (String pattern : getSegments()) {
      if (isRecursiveSegment(pattern)) {
        ++numRecursivePatterns;
      }
    }
    return numRecursivePatterns;
  }

  public ImmutableList<String> getSegments() {
    return segments;
  }

  private static IllegalArgumentException error(String message, String pattern) {
    return new IllegalArgumentException(message + " (in glob pattern '" + pattern + "')");
  }

  /** Parse unix glob pattern. */
  public static UnixGlobPattern parse(String pattern) {
    if (pattern.isEmpty()) {
      throw error("pattern cannot be empty", pattern);
    }
    if (pattern.charAt(0) == '/') {
      throw error("pattern cannot be absolute", pattern);
    }
    ImmutableList<String> segments = ImmutableList.copyOf(Splitter.on('/').split(pattern));
    for (String segment : segments) {
      if (segment.isEmpty()) {
        throw error("empty segment not permitted", pattern);
      }
      if (segment.equals(".") || segment.equals("..")) {
        throw error("segment '" + segment + "' not permitted", pattern);
      }
      if (segment.contains("**") && !segment.equals("**")) {
        throw error("recursive wildcard must be its own segment", pattern);
      }
    }
    return new UnixGlobPattern(pattern, segments);
  }

  @Override
  public String toString() {
    return pattern;
  }

  private boolean matchesFrom(
      int segmentIndex,
      ForwardRelativePath path,
      int pathIndex,
      @Nullable Map<String, Pattern> patternCache) {
    if (segmentIndex == segments.size()) {
      return pathIndex == path.getNameCount();
    }
    String segment = segments.get(segmentIndex);

    if (isRecursiveSegment(segment)) {
      if (matchesFrom(segmentIndex + 1, path, pathIndex, patternCache)) {
        return true;
      }
      if (pathIndex == path.getNameCount()) {
        return false;
      }
      return matchesFrom(segmentIndex, path, pathIndex + 1, patternCache);
    }

    if (pathIndex == path.getNameCount()) {
      return false;
    }
    if (!segmentMatches(segment, path.getSegment(pathIndex), patternCache)) {
      return false;
    }
    return matchesFrom(segmentIndex + 1, path, pathIndex + 1, patternCache);
  }

  /** Relative path matches pattern. */
  public boolean matches(ForwardRelativePath path, @Nullable Map<String, Pattern> patternCache) {
    return matchesFrom(0, path, 0, patternCache);
  }
}
