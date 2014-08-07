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

}
