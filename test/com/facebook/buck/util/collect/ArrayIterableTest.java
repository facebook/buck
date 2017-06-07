/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Iterator;
import java.util.NoSuchElementException;
import org.junit.Test;

public class ArrayIterableTest {

  @Test(expected = NullPointerException.class)
  public void testSingleParamConstructorRejectsNullArray() {
    ArrayIterable.of(/* array */ null);
  }

  @Test(expected = NullPointerException.class)
  public void testMultiParamConstructorRejectsNullArray() {
    ArrayIterable.of(/* array */ null, 0, 0);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testMultiParamConstructorRejectsNegativeStartIndex() {
    ArrayIterable.of(new Object[] {}, -1, 0);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testMultiParamConstructorRejectsStartIndexLargerThanArray() {
    ArrayIterable.of(new Object[] {}, 2, 0);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testMultiParamConstructorRejectsNegativeEndIndex() {
    ArrayIterable.of(new Object[] {}, 0, -1);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testMultiParamConstructorRejectsEndIndexLargerThanArray() {
    ArrayIterable.of(new Object[] {}, 0, 2);
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testMultiParamConstructorRejectsStartIndexLargerThanEndIndex() {
    ArrayIterable.of(new String[] {"a", "b", "c"}, 2, 1);
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testEmptyArrayIterable() {
    Iterable<Object> iterable = ArrayIterable.of(new Object[] {});
    Iterator<Object> iterator = iterable.iterator();
    assertFalse(iterator.hasNext());

    try {
      iterator.remove();
      fail("Should throw UnsupportedOperationException.");
    } catch (UnsupportedOperationException e) {
      // OK
    }

    try {
      iterator.next();
      fail("Should throw NoSuchElementException.");
    } catch (NoSuchElementException e) {
      // OK
    }
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testEmptySingletonIterable() {
    Iterable<String> iterable = ArrayIterable.of(new String[] {"only"});
    Iterator<String> iterator = iterable.iterator();

    // First element.
    assertTrue(iterator.hasNext());
    assertEquals("only", iterator.next());

    // Second element.
    assertFalse(iterator.hasNext());
    try {
      iterator.next();
      fail("Should throw NoSuchElementException.");
    } catch (NoSuchElementException e) {
      // OK
    }
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testMultiElementIterable() {
    Iterable<String> iterable = ArrayIterable.of(new String[] {"a", "b", "c"});
    Iterator<String> iterator = iterable.iterator();

    // First element.
    assertTrue(iterator.hasNext());
    assertEquals("a", iterator.next());

    // Second element.
    assertTrue(iterator.hasNext());
    assertEquals("b", iterator.next());

    // Third element.
    assertTrue(iterator.hasNext());
    assertEquals("c", iterator.next());

    try {
      iterator.remove();
      fail("Should throw UnsupportedOperationException.");
    } catch (UnsupportedOperationException e) {
      // OK
    }

    assertFalse("Iterator should be exhausted.", iterator.hasNext());
    try {
      iterator.next();
      fail("Should throw NoSuchElementException.");
    } catch (NoSuchElementException e) {
      // OK
    }
  }

  @Test
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void testMultiElementIterableWithIndexes() {
    Iterable<String> iterable =
        ArrayIterable.of(
            new String[] {"a", "b", "c", "d", "e"}, /* startIndex */ 1, /* endIndex */ 4);
    Iterator<String> iterator = iterable.iterator();

    // First element.
    assertTrue(iterator.hasNext());
    assertEquals("b", iterator.next());

    // Second element.
    assertTrue(iterator.hasNext());
    assertEquals("c", iterator.next());

    // Third element.
    assertTrue(iterator.hasNext());
    assertEquals("d", iterator.next());

    assertFalse("Iterator should be exhausted.", iterator.hasNext());
    try {
      iterator.next();
      fail("Should throw NoSuchElementException.");
    } catch (NoSuchElementException e) {
      // OK
    }
  }
}
