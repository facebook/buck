/*
 * Copyright 2014-present Facebook, Inc.
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

import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public class MoreIterablesTest {

  // Convert an iterable to a list.
  private static <T> ImmutableList<T> lstI(Iterable<T> inputs) {
    return ImmutableList.copyOf(inputs);
  }

  // Convert varargs to a list.
  @SafeVarargs
  private static <T> ImmutableList<T> lstV(T... inputs) {
    return ImmutableList.copyOf(inputs);
  }

  @Test
  public void testZipAndConcat() {
    assertEquals(
        lstV(),
        lstI(MoreIterables.zipAndConcat()));
    assertEquals(
        lstV("a"),
        lstI(MoreIterables.zipAndConcat(lstV("a"))));
    assertEquals(
        lstV("a", "b", "a", "b"),
        lstI(MoreIterables.zipAndConcat(lstV("a", "a", "a"), lstV("b", "b"))));
    assertEquals(
        lstV("a", "b", "c"),
        lstI(MoreIterables.zipAndConcat(lstV("a"), lstV("b"), lstV("c"))));
  }

  @Test
  public void testDedupKeepLast() {
    String[] emptyInput = new String[] {};
    Set<String> emptyDeduped = MoreIterables.dedupKeepLast(lstV(emptyInput));
    assertArrayAndSetEqual("empty", emptyInput, emptyDeduped);

    String[] noDups = new String[] {"a", "b", "c"};
    Set<String> noDupsDeduped = MoreIterables.dedupKeepLast(lstV(noDups));
    assertArrayAndSetEqual("noDups", noDups, noDupsDeduped);


    List<String> singleDup = lstV("a", "b", "a", "c");
    String[] singleDedupExpected = new String[]{"b", "a", "c"};
    Set<String> singleDedupActual = MoreIterables.dedupKeepLast(singleDup);
    assertArrayAndSetEqual("singleDup", singleDedupExpected, singleDedupActual);

    List<String> onlyDups = lstV("a", "a", "a");
    String[] onlyDupsDedupExpected = new String[]{"a"};
    Set<String> onlydupsDedupActual = MoreIterables.dedupKeepLast(onlyDups);
    assertArrayAndSetEqual("onlyDups", onlyDupsDedupExpected, onlydupsDedupActual);
  }

  private static void assertArrayAndSetEqual(String testName, String[] first, Set<String> second) {
    assertEquals(first.length, second.size());
    int i = 0;
    for (String s : second) {
      assertEquals(
          String.format("%s failed: Elements at %d are not equal", testName, i),
          first[i],
          s);
      i++;
    }
  }
}
