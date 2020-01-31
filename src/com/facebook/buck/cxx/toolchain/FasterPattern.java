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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pattern-like object which is compatible with Pattern but faster at handling simpler patterns like
 * {@code .*foo.*}.
 */
public abstract class FasterPattern implements AddsToRuleKey {

  private FasterPattern() {}

  /** Check if string does not contain anything which can be interpreted as regex. */
  private static boolean plainChars(String s) {
    for (char c : s.toCharArray()) {
      if ("[](){}.|\\?*+^$".indexOf(c) >= 0) {
        return false;
      }
    }
    return true;
  }

  private static final Pattern containsPattern = Pattern.compile("\\.\\*(.*)\\.\\*");
  private static final Pattern startsWithPattern = Pattern.compile("(.*)\\.\\*");

  /** Compile a pattern. Note this function compiles dot as {@link Pattern#DOTALL}. */
  public static FasterPattern compile(String pattern) {
    {
      Matcher matcher = containsPattern.matcher(pattern);
      if (matcher.matches() && plainChars(matcher.group(1))) {
        return new Contains(matcher.group(1));
      }
    }
    {
      Matcher matcher = startsWithPattern.matcher(pattern);
      if (matcher.matches() && plainChars(matcher.group(1))) {
        return new StartsWith(matcher.group(1));
      }
    }
    return new PatternPattern(Pattern.compile(pattern, Pattern.DOTALL));
  }

  /** Check if this pattern matches given string. */
  public abstract boolean matches(String s);

  /** Startswith pattern. */
  private static class StartsWith extends FasterPattern {
    @AddToRuleKey private final String prefix;

    private StartsWith(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public boolean matches(String s) {
      return s.startsWith(prefix);
    }
  }

  /** Contains substring pattern. */
  private static class Contains extends FasterPattern {
    @AddToRuleKey private final String needle;

    public Contains(String needle) {
      this.needle = needle;
    }

    @Override
    public boolean matches(String s) {
      // contains is quadratic, but still faster than pattern
      return s.contains(needle);
    }
  }

  /** Delegate to {@link java.util.regex.Pattern}. */
  private static class PatternPattern extends FasterPattern {
    @AddToRuleKey private final Pattern pattern;

    public PatternPattern(Pattern pattern) {
      this.pattern = pattern;
    }

    @Override
    public boolean matches(String s) {
      return pattern.matcher(s).matches();
    }
  }
}
