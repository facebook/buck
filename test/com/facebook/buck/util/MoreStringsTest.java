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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MoreStringsTest {

  @Test
  public void testIsEmpty() {
    assertTrue(MoreStrings.isEmpty(""));
    assertTrue(MoreStrings.isEmpty(new StringBuilder()));
    assertFalse(MoreStrings.isEmpty("text"));
    assertFalse(MoreStrings.isEmpty(new StringBuilder("text")));
  }

  @Test(expected = NullPointerException.class)
  public void testIsEmptyRejectsNull() {
    MoreStrings.isEmpty(null);
  }

  @Test
  public void testWithoutSuffix() {
    assertEquals("abc", MoreStrings.withoutSuffix("abcdef", "def"));
    assertEquals("", MoreStrings.withoutSuffix("abcdef", "abcdef"));
  }

  @Test
  public void testCapitalize() {
    assertEquals("Foo", MoreStrings.capitalize("foo"));
    assertEquals("F", MoreStrings.capitalize("f"));
    assertEquals("", MoreStrings.capitalize(""));
  }

  @Test(expected = NullPointerException.class)
  public void testCapitalizeRejectsNull() {
    MoreStrings.capitalize(null);
  }

  @Test
  public void testGetLevenshteinDistance() {
    assertEquals(
        "The distance between '' and 'BUILD' should be 5 (e.g., 5 insertions).",
        5,
        MoreStrings.getLevenshteinDistance("", "BUILD"));
    assertEquals(
        "'BUILD' and 'BUILD' should be identical.",
        0,
        MoreStrings.getLevenshteinDistance("BUILD", "BUILD"));
    assertEquals(
        "The distance between 'BIULD' and 'BUILD' should be 2 (e.g., 1 deletion + 1 insertion).",
        2,
        MoreStrings.getLevenshteinDistance("BIULD", "BUILD"));
    assertEquals(
        "The distance between 'INSTALL' and 'AUDIT' should be 7 (e.g., 5 substitutions + 2 deletions).",
        7,
        MoreStrings.getLevenshteinDistance("INSTALL", "AUDIT"));
    assertEquals(
        "The distance between 'aaa' and 'bbbbbb' should be 6 (e.g., 3 substitutions + 3 insertions).",
        6,
        MoreStrings.getLevenshteinDistance("aaa", "bbbbbb"));
    assertEquals(
        "The distance between 'build' and 'biuldd' should be 3 (e.g., 1 deletion + 2 insertions).",
        3,
        MoreStrings.getLevenshteinDistance("build", "biuldd"));
    assertEquals(
        "The distance between 'test' and 'tset' should be 2 (e.g., 1 deletion + 1 insertion).",
        2,
        MoreStrings.getLevenshteinDistance("test", "tset"));
  }
}
