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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import org.junit.Test;

public class RemoveClassesPatternsMatcherTest {
  @Test
  public void testEmptyMatcherRemovesNothing() {
    RemoveClassesPatternsMatcher matcher = RemoveClassesPatternsMatcher.EMPTY;

    assertFalse(matcher.test("com.example.Foo"));
    assertFalse(matcher.test(new ZipEntry("com/example/Foo.class")));
  }

  @Test
  public void testStringMatcher() {
    RemoveClassesPatternsMatcher matcher =
        new RemoveClassesPatternsMatcher(ImmutableSet.of(Pattern.compile("com[.]example[.]Foo")));

    assertTrue(matcher.test("com.example.Foo"));
  }

  @Test
  public void testZipEntryMatcher() {
    RemoveClassesPatternsMatcher matcher =
        new RemoveClassesPatternsMatcher(ImmutableSet.of(Pattern.compile("com[.]example[.]Foo")));

    assertTrue(matcher.test(new ZipEntry("com/example/Foo.class")));
  }

  @Test
  public void testNeverMatchesNonClasses() {
    RemoveClassesPatternsMatcher patternsMatcher =
        new RemoveClassesPatternsMatcher(ImmutableSet.of(Pattern.compile("test")));

    assertFalse(patternsMatcher.test(new ZipEntry("com/example/Foo/Foo.txt")));
  }

  @Test
  public void testMatchesPrefix() {
    RemoveClassesPatternsMatcher patternsMatcher =
        new RemoveClassesPatternsMatcher(ImmutableSet.of(Pattern.compile("test")));

    assertTrue(patternsMatcher.test("test_pattern"));
  }

  @Test
  public void testMatchesSuffix() {
    RemoveClassesPatternsMatcher patternsMatcher =
        new RemoveClassesPatternsMatcher(ImmutableSet.of(Pattern.compile("pattern")));

    assertTrue(patternsMatcher.test("test_pattern"));
  }

  @Test
  public void testMatchesInfix() {
    RemoveClassesPatternsMatcher patternsMatcher =
        new RemoveClassesPatternsMatcher(ImmutableSet.of(Pattern.compile("_")));

    assertTrue(patternsMatcher.test("test_pattern"));
  }

  @Test
  public void testExplicitMatchFullPatternSuccess() {
    RemoveClassesPatternsMatcher patternsMatcher =
        new RemoveClassesPatternsMatcher(ImmutableSet.of(Pattern.compile("^test_pattern$")));

    assertTrue(patternsMatcher.test("test_pattern"));
  }

  @Test
  public void testExplicitMatchFullPatternFailure() {
    RemoveClassesPatternsMatcher patternsMatcher =
        new RemoveClassesPatternsMatcher(ImmutableSet.of(Pattern.compile("^test_pattern$")));

    assertFalse(patternsMatcher.test("test_patterns"));
  }
}
