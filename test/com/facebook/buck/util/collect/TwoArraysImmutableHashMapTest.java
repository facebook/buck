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

package com.facebook.buck.util.collect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TwoArraysImmutableHashMapTest {

  public @Rule ExpectedException expectedException = ExpectedException.none();

  // Random is created per-test with specified seed, so tests are determinstic.
  private Random random = new Random(1);

  private <A> void assertIteratorsEqual(Iterator<A> guava, Iterator<A> twoArrays) {
    while (guava.hasNext()) {
      if (random.nextBoolean()) {
        assertTrue(twoArrays.hasNext());
      }
      assertEquals(guava.next(), twoArrays.next());
    }
    if (random.nextBoolean()) {
      assertFalse(twoArrays.hasNext());
    } else {
      try {
        twoArrays.next();
        fail();
      } catch (NoSuchElementException e) {
        // expected
      }
    }
    assertFalse(twoArrays.hasNext());
  }

  private <K, V> void assertTwoArraysCompatibleWithGuava(
      ImmutableMap<K, V> guava, TwoArraysImmutableHashMap<K, V> twoArrays) {
    assertEquals(guava, twoArrays);
    assertEquals(twoArrays, guava);
    assertEquals(guava.keySet(), twoArrays.keySet());
    assertEquals(twoArrays.keySet(), guava.keySet());
    assertEquals(guava.entrySet(), twoArrays.entrySet());
    assertEquals(twoArrays.entrySet(), guava.entrySet());
    assertEquals(guava.toString(), twoArrays.toString());
    assertEquals(guava.hashCode(), twoArrays.hashCode());
    assertIteratorsEqual(guava.entrySet().iterator(), twoArrays.entrySet().iterator());
    assertIteratorsEqual(guava.keySet().iterator(), twoArrays.keySet().iterator());
    assertIteratorsEqual(guava.values().iterator(), twoArrays.values().iterator());
  }

  private TwoArraysImmutableHashMap<String, Integer> twoArraysMap(int size) {
    return IntStream.range(0, size)
        .boxed()
        .collect(TwoArraysImmutableHashMap.toMap(Object::toString, i -> i));
  }

  @Test
  public void basic() {
    for (int size : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 16, 31, 32, 33, 56, 67, 145}) {
      TwoArraysImmutableHashMap<String, Integer> map = twoArraysMap(size);

      for (int i = 0; i < size; ++i) {
        assertEquals(i, (int) map.get(Integer.toString(i)));
      }
    }
  }

  @Test
  public void builderWithCapacity() {
    TwoArraysImmutableHashMap.Builder<String, Integer> builder =
        TwoArraysImmutableHashMap.builderWithExpectedSize(2);
    builder.put("17", 17);
    builder.put("19", 19);
    TwoArraysImmutableHashMap<String, Integer> map = builder.build();
    assertEquals(ImmutableMap.of("17", 17, "19", 19), map);
  }

  @Test
  public void compatWithGuavaSimple() {
    for (int size : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 17, 44, 566, 44678}) {
      TwoArraysImmutableHashMap.Builder<Integer, String> twoArraysBuilder =
          TwoArraysImmutableHashMap.builder();
      ImmutableMap.Builder<Integer, String> guavaBuilder = ImmutableMap.builder();
      for (int i = 0; i < size; ++i) {
        twoArraysBuilder.put(i, Integer.toString(i));
        guavaBuilder.put(i, Integer.toString(i));
      }
      assertTwoArraysCompatibleWithGuava(guavaBuilder.build(), twoArraysBuilder.build());
    }
  }

  @Test
  public void compatWithGuavaRandom() {
    for (int i = 0; i != 10000; ++i) {
      int size = random.nextInt(150);

      HashSet<String> seenKeys = new HashSet<>();

      ImmutableMap.Builder<String, Integer> guavaBuilder = ImmutableMap.builder();
      TwoArraysImmutableHashMap.Builder<String, Integer> twoArraysBuilder =
          TwoArraysImmutableHashMap.builder();

      for (int j = 0; j < size; ++j) {
        int key;
        for (; ; ) {
          key = random.nextInt(1000);
          if (seenKeys.add(Integer.toString(key))) {
            break;
          }
        }
        twoArraysBuilder.put(Integer.toString(key), key);
        guavaBuilder.put(Integer.toString(key), key);
      }

      assertTwoArraysCompatibleWithGuava(guavaBuilder.build(), twoArraysBuilder.build());
    }
  }

  @Test
  public void collectFromStream() {
    for (int size : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 15, 16, 17, 33, 99, 156, 8746}) {
      TwoArraysImmutableHashMap<String, Integer> map = twoArraysMap(size);
      for (int i = 0; i < size; ++i) {
        assertEquals(i, (int) map.get(Integer.toString(i)));
      }
    }
  }

  @Test
  public void collectFromParallelStream() {
    for (int size : new int[] {0, 1, 2, 3, 4, 5, 8, 9, 11, 15, 156, 8746, 10223, 12004}) {
      TwoArraysImmutableHashMap<String, Integer> map =
          IntStream.range(0, size)
              .parallel()
              .boxed()
              .collect(TwoArraysImmutableHashMap.toMap(Object::toString, i -> i));
      for (int i = 0; i < size; ++i) {
        assertEquals(i, (int) map.get(Integer.toString(i)));
      }
    }
  }

  @Test
  public void nonUniqueKeysOf2() {
    expectedException.expect(IllegalStateException.class);
    TwoArraysImmutableHashMap.of("1", true, "1", false);
  }

  @Test
  public void nonUniqueKeysOf3() {
    expectedException.expect(IllegalStateException.class);
    TwoArraysImmutableHashMap.of("1", true, "1", false, "2", true);
  }

  @Test
  public void mapValues() {
    assertEquals(
        TwoArraysImmutableHashMap.of(),
        TwoArraysImmutableHashMap.of()
            .mapValues(
                (k, x) -> {
                  throw new AssertionError();
                }));
    assertEquals(
        TwoArraysImmutableHashMap.of("1", "10"),
        TwoArraysImmutableHashMap.of("1", 10)
            .mapValues(
                (k, x) -> {
                  assertEquals("1", k);
                  return Integer.toString(x);
                }));
    assertEquals(
        TwoArraysImmutableHashMap.of("1", 11, "2", 21),
        TwoArraysImmutableHashMap.of("1", 10, "2", 20)
            .mapValues(
                (k, x) -> {
                  assertTrue(k.equals("1") && x.equals(10) || k.equals("2") && x.equals(20));
                  return x + 1;
                }));
  }
}
