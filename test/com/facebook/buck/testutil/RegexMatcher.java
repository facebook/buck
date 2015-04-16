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

package com.facebook.buck.testutil;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.regex.Pattern;

/**
 * Assertion helper for hamcrest library that checks if a given string matches a regular expression
 * or whether it contains a fragment that matches some pattern.
 */
public class RegexMatcher extends TypeSafeMatcher<CharSequence> {

  protected final Pattern pattern;

  public RegexMatcher(Pattern pattern) {
    this.pattern = pattern;
  }

  @Override
  protected boolean matchesSafely(CharSequence charSequence) {
    return pattern.matcher(charSequence).matches();
  }

  @Override
  public void describeTo(Description description) {
    description
        .appendText("a string with pattern \"")
        .appendText(pattern.toString())
        .appendText("\"");
  }

  @Override
  public void describeMismatchSafely(CharSequence charSequence, Description mismatchDescription) {
    mismatchDescription
        .appendText("was \"")
        .appendText(charSequence.toString())
        .appendText("\"");
  }

  private static class RegexFindMatcher extends RegexMatcher {

    public RegexFindMatcher(Pattern pattern) {
      super(pattern);
    }

    @Override
    protected boolean matchesSafely(CharSequence charSequence) {
      return pattern.matcher(charSequence).find();
    }

    @Override
    public void describeTo(Description description) {
      description
          .appendText("a string containing pattern \"")
          .appendText(pattern.toString())
          .appendText("\"");
    }
  }

  /**
   * Creates a matcher that checks if the examined {@link CharSequence} matches the specified
   * {@link Pattern}.
   * <p/>
   * For example:
   * <pre>assertThat("Test-42", matchesPattern(Pattern.compile("^Test-[0-9]+$")))</pre>
   *
   * @param pattern
   *     the pattern that the returned matcher will use to match any examined {@link CharSequence}
   */
  public static Matcher<CharSequence> matchesPattern(Pattern pattern) {
    return new RegexMatcher(pattern);
  }

  /**
   * Creates a matcher that checks if the examined {@link CharSequence} matches the specified
   * regular expression.
   * <p/>
   * For example:
   * <pre>assertThat("Example-43", matchesRegex("^Example-[0-9]+$"))</pre>
   *
   * @param regex
   *     the regular expression that the returned matcher will use to match any examined
   *     {@link CharSequence}
   */
  public static Matcher<CharSequence> matchesRegex(String regex) {
    return matchesPattern(Pattern.compile(regex));
  }

  /**
   * Creates a matcher that checks if a specified {@link Pattern} can be found in the examined
   * {@link CharSequence}
   * <p/>
   * For example:
   * <pre>assertThat("Test-42-Test", containsPattern(Pattern.compile("[0-9]+")))</pre>
   *
   * @param pattern
   *     the pattern that the returned matcher will use to find a match in any examined
   *     {@link CharSequence}
   */
  public static Matcher<CharSequence> containsPattern(Pattern pattern) {
    return new RegexFindMatcher(pattern);
  }

  /**
   * Creates a matcher that checks if a specified regular expression can be found in the examined
   * {@link CharSequence}
   * <p/>
   * For example:
   * <pre>assertThat("Test-571-Test", containsRegex("[0-9]+"))</pre>
   *
   * @param regex
   *     the regular expression that the returned matcher will use to find a match in any examined
   *     {@link CharSequence}
   */
  public static Matcher<CharSequence> containsRegex(String regex) {
    return containsPattern(Pattern.compile(regex));
  }

}
