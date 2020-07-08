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

package com.facebook.buck.skylark.parser.pojoizer;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.Test;

public class BuildFileManifestPojoizerTest {

  @Test
  public void canPojoizePojoTypeAsIs() throws Exception {
    String s = "I am a string";
    assertEquals(s, BuildFileManifestPojoizer.convertToPojo(s));
  }

  @Test
  public void canPojoizeMapToImmutableMap() throws Exception {
    HashMap<String, String> map = new HashMap<>();
    map.put("a", "b");
    map.put("c", "d");
    assertEquals(ImmutableMap.copyOf(map), BuildFileManifestPojoizer.convertToPojo(map));
  }

  @Test
  public void canPojoizeSortedMapToImmutableSortedMap() throws Exception {
    TreeMap<String, String> map = new TreeMap<>(Comparator.reverseOrder());
    map.put("a", "b");
    map.put("c", "d");
    assertEquals(
        ImmutableSortedMap.copyOf(map.entrySet(), Comparator.reverseOrder()),
        BuildFileManifestPojoizer.convertToPojo(map));
  }

  @Test
  public void canPojoizeMapRecursively() throws Exception {
    HashMap<String, String> map1 = new HashMap<>();
    map1.put("a1", "a2");

    HashMap<String, String> map2 = new HashMap<>();
    map1.put("b1", "b2");

    HashMap<String, HashMap<String, String>> map = new HashMap<>();
    map.put("a", map1);
    map.put("b", map2);

    assertEquals(
        ImmutableMap.of("a", ImmutableMap.copyOf(map1), "b", ImmutableMap.copyOf(map2)),
        BuildFileManifestPojoizer.convertToPojo(map));
  }

  @Test
  public void canPojoizeSetToImmutableSet() throws Exception {
    HashSet<Integer> set = new HashSet<>();
    set.add(1);
    set.add(2);
    assertEquals(ImmutableSet.copyOf(set), BuildFileManifestPojoizer.convertToPojo(set));
  }

  @Test
  public void canPojoizeSetRecursively() throws Exception {
    HashSet<Integer> set1 = new HashSet<>();
    set1.add(1);

    HashSet<Integer> set2 = new HashSet<>();
    set2.add(2);

    HashSet<HashSet<Integer>> set = new HashSet<>();
    set.add(set1);
    set.add(set2);

    assertEquals(
        ImmutableSet.of(ImmutableSet.copyOf(set1), ImmutableSet.copyOf(set2)),
        BuildFileManifestPojoizer.convertToPojo(set));
  }

  @Test
  public void canPojoizeSortedSetToImmutableSortedSet() throws Exception {
    TreeSet<Integer> set = new TreeSet<>(Comparator.reverseOrder());
    set.add(1);
    set.add(2);
    assertEquals(
        ImmutableSortedSet.copyOf(Comparator.reverseOrder(), set),
        BuildFileManifestPojoizer.convertToPojo(set));
  }

  @Test
  public void canPojoizeListToImmutableList() throws Exception {
    ArrayList<String> list = new ArrayList<>();
    list.add("a");
    list.add("b");
    assertEquals(ImmutableList.copyOf(list), BuildFileManifestPojoizer.convertToPojo(list));
  }

  @Test
  public void canPojoizeListRecursively() throws Exception {
    ArrayList<String> list1 = new ArrayList<>();
    list1.add("a");

    ArrayList<String> list2 = new ArrayList<>();
    list2.add("b");

    ArrayList<ArrayList<String>> list = new ArrayList<>();
    list.add(list1);
    list.add(list2);
    assertEquals(
        ImmutableList.of(ImmutableList.copyOf(list1), ImmutableList.copyOf(list2)),
        BuildFileManifestPojoizer.convertToPojo(list));
  }
}
