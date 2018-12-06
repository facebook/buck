/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.api;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.Test;

public class BuildFileManifestPojoizerTest {

  @Test
  public void canPojoizePojoTypeAsIs() {
    String s = "I am a string";
    assertEquals(s, BuildFileManifestPojoizer.of().convertToPojo(s));
  }

  @Test
  public void canPojoizeMapToImmutableMap() {
    HashMap<String, String> map = new HashMap<>();
    map.put("a", "b");
    map.put("c", "d");
    assertEquals(ImmutableMap.copyOf(map), BuildFileManifestPojoizer.of().convertToPojo(map));
  }

  @Test
  public void canPojoizeSortedMapToImmutableSortedMap() {
    TreeMap<String, String> map = new TreeMap<>(Comparator.reverseOrder());
    map.put("a", "b");
    map.put("c", "d");
    assertEquals(
        ImmutableSortedMap.copyOf(map.entrySet(), Comparator.reverseOrder()),
        BuildFileManifestPojoizer.of().convertToPojo(map));
  }

  @Test
  public void canPojoizeMapRecursively() {
    HashMap<String, String> map1 = new HashMap<>();
    map1.put("a1", "a2");

    HashMap<String, String> map2 = new HashMap<>();
    map1.put("b1", "b2");

    HashMap<String, HashMap<String, String>> map = new HashMap<>();
    map.put("a", map1);
    map.put("b", map2);

    assertEquals(
        ImmutableMap.of("a", ImmutableMap.copyOf(map1), "b", ImmutableMap.copyOf(map2)),
        BuildFileManifestPojoizer.of().convertToPojo(map));
  }

  @Test
  public void canPojoizeSetToImmutableSet() {
    HashSet<Integer> set = new HashSet<>();
    set.add(1);
    set.add(2);
    assertEquals(ImmutableSet.copyOf(set), BuildFileManifestPojoizer.of().convertToPojo(set));
  }

  @Test
  public void canPojoizeSetRecursively() {
    HashSet<Integer> set1 = new HashSet<>();
    set1.add(1);

    HashSet<Integer> set2 = new HashSet<>();
    set2.add(2);

    HashSet<HashSet<Integer>> set = new HashSet<>();
    set.add(set1);
    set.add(set2);

    assertEquals(
        ImmutableSet.of(ImmutableSet.copyOf(set1), ImmutableSet.copyOf(set2)),
        BuildFileManifestPojoizer.of().convertToPojo(set));
  }

  @Test
  public void canPojoizeSortedSetToImmutableSortedSet() {
    TreeSet<Integer> set = new TreeSet<>(Comparator.reverseOrder());
    set.add(1);
    set.add(2);
    assertEquals(
        ImmutableSortedSet.copyOf(Comparator.reverseOrder(), set),
        BuildFileManifestPojoizer.of().convertToPojo(set));
  }

  @Test
  public void canPojoizeListToImmutableList() {
    ArrayList<String> list = new ArrayList<>();
    list.add("a");
    list.add("b");
    assertEquals(ImmutableList.copyOf(list), BuildFileManifestPojoizer.of().convertToPojo(list));
  }

  @Test
  public void canPojoizeListRecursively() {
    ArrayList<String> list1 = new ArrayList<>();
    list1.add("a");

    ArrayList<String> list2 = new ArrayList<>();
    list2.add("b");

    ArrayList<ArrayList<String>> list = new ArrayList<>();
    list.add(list1);
    list.add(list2);
    assertEquals(
        ImmutableList.of(ImmutableList.copyOf(list1), ImmutableList.copyOf(list2)),
        BuildFileManifestPojoizer.of().convertToPojo(list));
  }

  private static class CustomClass<T> {
    public T prop1;

    public CustomClass(T prop1) {
      this.prop1 = prop1;
    }

    public static <T> CustomClass of(T prop1) {
      return new CustomClass(prop1);
    }
  }

  @Test
  public void willUseCustomTransformer() {
    CustomClass<String> customClass = CustomClass.of("a");

    BuildFileManifestPojoizer pojoizer =
        BuildFileManifestPojoizer.of()
            .addPojoTransformer(
                PojoTransformer.of(
                    customClass.getClass(), obj -> ImmutableList.of(customClass.prop1)));
    assertEquals(ImmutableList.of(customClass.prop1), pojoizer.convertToPojo(customClass));
  }

  @Test
  public void customTransformerOverridesDefaultTransformer() {
    ArrayList<String> list = new ArrayList<>();
    list.add("a");

    BuildFileManifestPojoizer pojoizer =
        BuildFileManifestPojoizer.of()
            .addPojoTransformer(
                PojoTransformer.of(list.getClass(), obj -> ((ArrayList<String>) obj).get(0)));

    assertEquals("a", pojoizer.convertToPojo(list));
  }

  @Test
  public void customTransformerCanBeRecursive() {

    CustomClass<List<CustomClass>> recursiveClass =
        CustomClass.of(Arrays.asList(CustomClass.of("a"), CustomClass.of("b")));

    BuildFileManifestPojoizer pojoizer = BuildFileManifestPojoizer.of();

    pojoizer.addPojoTransformer(
        PojoTransformer.of(
            recursiveClass.getClass(),
            obj -> {
              CustomClass<Object> rclass = (CustomClass<Object>) obj;

              // full recurse into class hierarchy, should engage all available transformers,
              // so all internal maps and lists are transformed too
              return ImmutableList.of(pojoizer.convertToPojo(rclass.prop1));
            }));

    assertEquals(
        ImmutableList.of(ImmutableList.of(ImmutableList.of("a"), ImmutableList.of("b"))),
        pojoizer.convertToPojo(recursiveClass));
  }
}
