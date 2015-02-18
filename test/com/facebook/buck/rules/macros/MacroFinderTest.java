/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.Pair;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

public class MacroFinderTest {

  private static final MacroFinder FINDER = new MacroFinder();

  private <K, V> ImmutableMap<K, V> pairListToMap(ImmutableList<Pair<K, V>> pairs) {
    ImmutableMap.Builder<K, V> map = ImmutableMap.builder();
    for (Pair<K, V> pair : pairs) {
      map.put(pair.getFirst(), pair.getSecond());
    }
    return map.build();
  }

  @Test
  public void findAll() throws MacroException {
    ImmutableMap<String, String> expectedResults =
        ImmutableMap.of(
            "macro1", "",
            "macro2", "arg");
    ImmutableList<Pair<String, String>> actualResults =
        FINDER.findAll(expectedResults.keySet(), "hello world $(macro1) and $(macro2 arg)");
    assertEquals(expectedResults, pairListToMap(actualResults));
  }

  @Test(expected = MacroException.class)
  public void findAllUnexpectedMacro() throws MacroException {
    FINDER.findAll(ImmutableSet.<String>of(), "hello world $(macro)");
  }

  @Test
  public void replace() throws MacroException {
    Function<String, String> replacer =
        Functions.forMap(
        ImmutableMap.of(
            "arg1", "something",
            "arg2", "something else"));
    String actual = FINDER.replace(
        ImmutableMap.<String, MacroReplacer>of("macro", new FunctionMacroReplacer(replacer)),
        "hello $(macro arg1) goodbye $(macro arg2)");
    assertEquals("hello something goodbye something else", actual);
  }

}
