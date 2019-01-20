/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.collect;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SortedSetsTest {
  private final ImmutableSortedSet<String> empty = ImmutableSortedSet.of();
  private final ImmutableSortedSet<String> abc = ImmutableSortedSet.of("a", "b", "c");
  private final ImmutableSortedSet<String> def = ImmutableSortedSet.of("d", "e", "f");
  private final ImmutableSortedSet<String> ace = ImmutableSortedSet.of("a", "c", "e");
  private final ImmutableSortedSet<String> bdf = ImmutableSortedSet.of("b", "d", "f");
  private final ImmutableSortedSet<String> abcdef =
      ImmutableSortedSet.of("a", "b", "c", "d", "e", "f");
  private final ImmutableSortedSet<String> cd = ImmutableSortedSet.of("c", "d");

  private final ImmutableSortedSet<String> cba =
      new ImmutableSortedSet.Builder<String>(Ordering.natural().reverse())
          .add("a")
          .add("b")
          .add("c")
          .build();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void differentComparatorsThrows() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("different comparators");
    SortedSets.union(abc, cba);
  }

  @Test
  public void equivalentNaturalComparatorsAreFine() {
    assertIterateSame(
        ImmutableSortedSet.of("a", "b"),
        SortedSets.union(
            ImmutableSortedSet.copyOf(Ordering.natural(), ImmutableList.of("a")),
            ImmutableSortedSet.copyOf(Comparator.naturalOrder(), ImmutableList.of("b"))));
    assertIterateSame(
        ImmutableSortedSet.of("a", "b"),
        SortedSets.union(
            ImmutableSortedSet.copyOf(Ordering.natural(), ImmutableList.of("a")),
            new TreeSet<>(ImmutableList.of("b"))));
  }

  @Test
  public void comparator() {
    assertEquals(abc.comparator(), SortedSets.union(abc, def).comparator());
  }

  @Test
  public void iterator() {
    assertIterateSame(abcdef, SortedSets.union(abc, def));
    assertIterateSame(abcdef, SortedSets.union(def, abc));
    assertIterateSame(abcdef, SortedSets.union(abc, abcdef));
    assertIterateSame(abcdef, SortedSets.union(ace, bdf));
    assertIterateSame(abcdef, SortedSets.union(bdf, ace));
    assertIterateSame(abc, SortedSets.union(abc, empty));
    assertIterateSame(abc, SortedSets.union(empty, abc));
    assertIterateSame(empty, SortedSets.union(empty, empty));
  }

  @Test
  public void subSet() {
    assertIterateSame(abc, SortedSets.union(abc, def).subSet("a", "d"));
    assertIterateSame(abc, SortedSets.union(abc, abcdef).subSet("a", "d"));
    assertIterateSame(cd, SortedSets.union(abc, def).subSet("c", "e"));
    assertIterateSame(empty, SortedSets.union(empty, empty).subSet("a", "g"));
  }

  @Test
  public void headSet() {
    assertIterateSame(abc, SortedSets.union(abc, def).headSet("d"));
    assertIterateSame(abc, SortedSets.union(abc, abcdef).headSet("d"));
    assertIterateSame(empty, SortedSets.union(empty, empty).headSet("d"));
  }

  @Test
  public void tailSet() {
    assertIterateSame(def, SortedSets.union(abc, def).tailSet("d"));
    assertIterateSame(def, SortedSets.union(abc, abcdef).tailSet("d"));
    assertIterateSame(empty, SortedSets.union(empty, empty).tailSet("d"));
  }

  @Test
  public void first() {
    assertEquals("a", SortedSets.union(abc, def).first());
    assertEquals("a", SortedSets.union(def, abc).first());
    assertEquals("a", SortedSets.union(abc, empty).first());
    assertEquals("a", SortedSets.union(empty, abc).first());
  }

  @Test(expected = NoSuchElementException.class)
  public void firstEmpty() {
    SortedSets.union(empty, empty).first();
  }

  @Test
  public void last() {
    assertEquals("f", SortedSets.union(abc, def).last());
    assertEquals("f", SortedSets.union(def, abc).last());
    assertEquals("c", SortedSets.union(abc, empty).last());
    assertEquals("c", SortedSets.union(empty, abc).last());
  }

  @Test(expected = NoSuchElementException.class)
  public void lastEmpty() {
    SortedSets.union(empty, empty).last();
  }

  @Test
  public void size() {
    assertEquals(6, SortedSets.union(abc, def).size());
    assertEquals(6, SortedSets.union(def, abc).size());
    assertEquals(6, SortedSets.union(abc, abcdef).size());
    assertEquals(6, SortedSets.union(abcdef, abc).size());
    assertEquals(3, SortedSets.union(abc, empty).size());
    assertEquals(3, SortedSets.union(empty, abc).size());
    assertEquals(0, SortedSets.union(empty, empty).size());
  }

  @Test
  public void isEmpty() {
    assertFalse(SortedSets.union(abc, def).isEmpty());
    assertFalse(SortedSets.union(def, abc).isEmpty());
    assertFalse(SortedSets.union(abc, abcdef).isEmpty());
    assertFalse(SortedSets.union(abcdef, abc).isEmpty());
    assertFalse(SortedSets.union(abc, empty).isEmpty());
    assertFalse(SortedSets.union(empty, abc).isEmpty());
    assertTrue(SortedSets.union(empty, empty).isEmpty());
  }

  @Test
  public void contains() {
    assertTrue(SortedSets.union(abc, def).contains("a"));
    assertFalse(SortedSets.union(abc, def).contains("g"));
    assertTrue(SortedSets.union(def, abc).contains("a"));
    assertFalse(SortedSets.union(def, abc).contains("g"));
    assertTrue(SortedSets.union(abc, abcdef).contains("a"));
    assertFalse(SortedSets.union(abc, abcdef).contains("g"));
    assertTrue(SortedSets.union(abcdef, abc).contains("a"));
    assertFalse(SortedSets.union(abcdef, abc).contains("g"));
    assertTrue(SortedSets.union(abc, empty).contains("a"));
    assertFalse(SortedSets.union(abc, empty).contains("g"));
    assertTrue(SortedSets.union(empty, abc).contains("a"));
    assertFalse(SortedSets.union(empty, abc).contains("g"));
    assertFalse(SortedSets.union(empty, empty).contains("a"));
    assertFalse(SortedSets.union(empty, empty).contains("g"));
  }

  @Test
  public void containsAll() {
    assertTrue(SortedSets.union(abc, abcdef).containsAll(abc));
    assertFalse(SortedSets.union(abc, bdf).containsAll(abcdef));
  }

  @Test
  public void toArray() {
    assertArrayEquals(new Object[] {"a", "b", "c"}, SortedSets.union(abc, empty).toArray());
    assertArrayEquals(
        new Object[] {"a", "b", "c", "d", "e", "f"}, SortedSets.union(abc, abcdef).toArray());
    assertArrayEquals(new Object[] {}, SortedSets.union(empty, empty).toArray());
  }

  @Test
  public void toArray2() {
    assertArrayEquals(
        new Object[] {"a", "b", "c"}, SortedSets.union(abc, empty).toArray(new Object[0]));
    assertArrayEquals(
        new Object[] {"a", "b", "c", "d", "e", "f"},
        SortedSets.union(abc, abcdef).toArray(new Object[0]));
    assertArrayEquals(new Object[] {}, SortedSets.union(empty, empty).toArray(new Object[0]));

    Object[] toFill = new Object[10];
    SortedSets.union(abc, empty).toArray(toFill);
    assertArrayEquals(new Object[] {"a", "b", "c", null}, Arrays.copyOfRange(toFill, 0, 4));

    SortedSets.union(abc, abcdef).toArray(toFill);
    assertArrayEquals(
        new Object[] {"a", "b", "c", "d", "e", "f", null}, Arrays.copyOfRange(toFill, 0, 7));
    SortedSets.union(empty, empty).toArray(toFill);
    assertEquals(null, toFill[0]);
  }

  @Test
  public void testToString() {
    assertEquals("[]", SortedSets.union(empty, empty).toString());
    assertEquals("[a, b, c]", SortedSets.union(abc, abc).toString());
  }

  @Test
  @SuppressWarnings(
      "PMD.UseAssertEqualsInsteadOfAssertTrue") // The exact call and ordering matters here, so
  // explicity call equals rather than assertEquals.
  public void equals() {
    assertTrue(abc.equals(SortedSets.union(abc, empty)));
    assertTrue(abc.equals(SortedSets.union(empty, abc)));
    assertTrue(SortedSets.union(abc, empty).equals(abc));
    assertTrue(SortedSets.union(empty, abc).equals(abc));
    assertTrue(SortedSets.union(abcdef, abc).equals(abcdef));
    assertTrue(SortedSets.union(abc, abcdef).equals(abcdef));
    assertTrue(abcdef.equals(SortedSets.union(abcdef, abc)));
    assertTrue(abcdef.equals(SortedSets.union(abc, abcdef)));

    assertFalse(abc.equals(SortedSets.union(abc, abcdef)));
    assertFalse(SortedSets.union(abc, abcdef).equals(abc));
  }

  @Test
  public void testHashCode() {
    assertEquals(
        SortedSets.union(empty, abcdef).hashCode(), SortedSets.union(abcdef, abc).hashCode());
  }

  private static <T> void assertIterateSame(
      Iterable<T> expectedIterable, Iterable<T> actualIterable) {
    Iterator<T> expected = expectedIterable.iterator();
    Iterator<T> actual = actualIterable.iterator();
    int index = 0;
    while (expected.hasNext() && actual.hasNext()) {
      T left = expected.next();
      T right = actual.next();
      assertEquals(
          String.format("Element %d differed - expected %s actual %s", index++, left, right),
          left,
          right);
    }
    if (expected.hasNext()) {
      fail(
          String.format(
              "Expected more elements, missing from element %d (%s)", index, expected.next()));
    }
    if (actual.hasNext()) {
      fail(
          String.format(
              "Actually had more elements, extra from element %d: %s", index, actual.next()));
    }
  }
}
