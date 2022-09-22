/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class DefaultJavaLibraryRulesTest {

  @Test
  public void testJavaVersionPatternRegex() {
    verifyRegexMatch(7, "1.7");
    verifyRegexMatch(7, "7");
    verifyRegexMatch(8, "1.8");
    verifyRegexMatch(8, "8");
    verifyRegexMatch(9, "9");
    verifyRegexMatch(10, "10");
    verifyRegexMatch(11, "11");
  }

  private void verifyRegexMatch(int expectedValue, String javaVersion) {
    Pattern regex = DefaultJavaLibraryRules.JAVA_VERSION_PATTERN;
    Matcher matcher = regex.matcher(javaVersion);
    assertTrue(matcher.find());
    assertEquals(expectedValue, Integer.parseInt(matcher.group("version")));
  }
}
