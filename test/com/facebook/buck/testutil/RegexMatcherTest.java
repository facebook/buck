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

import static com.facebook.buck.testutil.OutputHelper.createBuckTestOutputLineRegex;
import static com.facebook.buck.testutil.RegexMatcher.containsPattern;
import static com.facebook.buck.testutil.RegexMatcher.containsRegex;
import static com.facebook.buck.testutil.RegexMatcher.matchesPattern;
import static com.facebook.buck.testutil.RegexMatcher.matchesRegex;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;

import org.junit.Test;

import java.util.regex.Pattern;

public class RegexMatcherTest {

  @Test
  public void testRegexMatcherMatchesPatternOrRegex() {
    assertThat("Test-42", matchesPattern(Pattern.compile("^Test-[0-9]+$")));
    assertThat("Example-43", matchesRegex("^Example-[0-9]+$"));

    assertThat("Foo-42", not(matchesPattern(Pattern.compile("^Test-[0-9]+$"))));
    assertThat("Example-43XYZ", not(matchesRegex("^Example-[0-9]+$")));
  }

  @Test
  public void testRegexMatcherContainsPatternOrRegex() {
    assertThat("Test-42-Test", containsPattern(Pattern.compile("[0-9]+")));
    assertThat("Test-571-Test", containsRegex("[0-9]+"));

    assertThat("foo-bar", not(containsPattern(Pattern.compile("[0-9]+"))));
    assertThat("fooooo", not(containsRegex("[0-9]+")));
  }

  @Test
  public void testBuckTestOutputLineRegex() {
    assertThat("PASS    <100ms  1 Passed   0 Skipped   0 Failed   com.example.PassingTest",
        matchesRegex(createBuckTestOutputLineRegex("PASS", 1, 0, 0, "com.example.PassingTest")));
    assertThat("FAIL       60s  0 Passed   0 Skipped   1 Failed   com.example.FailingTest",
        matchesRegex(createBuckTestOutputLineRegex("FAIL", 0, 0, 1, "com.example.FailingTest")));

    assertThat("foobar",
        not(matchesRegex(createBuckTestOutputLineRegex("PASS", 1, 0, 0, "com.example.Test"))));
    assertThat("FAIL       60s  0 Passed   0 Skipped   1 Failed   com.example.Test",
        not(matchesRegex(createBuckTestOutputLineRegex("PASS", 1, 0, 0, "com.example.Test"))));
  }

}
