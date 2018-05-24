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

package com.facebook.buck.core.exceptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ThrowableCauseIterableTest {

  @Rule public ExpectedException expected = ExpectedException.none();

  /**
   * Create chain of exceptions, where the bottom one's message is a string "0" and the top one's
   * message is str(size-1)
   */
  private Exception getExceptionChain(int size) {
    Exception root = new Exception("0");
    for (int i = 1; i < size; i++) {
      Exception ex = new Exception(String.valueOf(i), root);
      root = ex;
    }
    return root;
  }

  @Test
  public void singleElement() {
    Exception ex = new Exception("Single element");
    Throwable last = Iterables.getLast(ThrowableCauseIterable.of(ex));
    assertSame(ex, last);
  }

  @Test
  public void iteration() {

    final int size = 3;
    Exception root = getExceptionChain(size);

    List<String> exceptionMessages = new ArrayList();
    for (Throwable t : ThrowableCauseIterable.of(root)) {
      exceptionMessages.add(t.getMessage());
    }
    exceptionMessages = Lists.reverse(exceptionMessages);

    assertEquals(size, exceptionMessages.size());

    for (int i = 0; i < size; i++) {
      assertEquals(String.valueOf(i), exceptionMessages.get(i));
    }
  }

  @Test
  public void loopToFirst() {
    final int size = 3;
    Exception root = getExceptionChain(size);

    Iterable<Throwable> iterable = ThrowableCauseIterable.of(root);

    // set cause of last element to root element
    Iterables.getLast(iterable).initCause(root);

    // now iterate for real
    Throwable last = Iterables.getLast(iterable);

    // assert that we stopped at the beginning of the loop, which is the last element in a chain
    assertEquals("0", last.getMessage());
  }

  @Test
  public void loopToSecond() {
    final int size = 3;
    Exception root = getExceptionChain(size);

    Iterable<Throwable> iterable = ThrowableCauseIterable.of(root);

    // set cause of last element to 2nd element
    Iterables.getLast(iterable).initCause(root.getCause());

    // now iterate for real
    Throwable last = Iterables.getLast(iterable);

    // assert that we stopped at the beginning of the loop, which is the last element in a chain
    assertEquals("0", last.getMessage());
  }

  @Test
  public void nextThrowIfEmpty() {
    final int size = 2;
    Exception root = getExceptionChain(size);

    Iterator<Throwable> iterator = ThrowableCauseIterable.of(root).iterator();

    for (int i = 0; i <= size; i++) {
      if (i == size) {
        // we expect it to throw exactly when it runs out of bounds
        expected.expect(NoSuchElementException.class);
      }
      iterator.next();
    }
  }
}
