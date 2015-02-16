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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * {@link Iterable} of an array object that does not make a copy of the array.
 * <p>
 * Designed to be used when it is too expensive to copy an array to a {@link java.util.List} and use
 * the list as an {@link Iterable} instead.
 */
public class ArrayIterable<T> implements Iterable<T> {

  private final T[] array;
  private final int startIndex;
  private final int endIndex;

  /**
   * @param startIndex inclusive
   * @param endIndex exclusive
   */
  private ArrayIterable(T[] array, int startIndex, int endIndex) {
    // No precondition checks here: they have already been performed in the of() method.
    this.array = array;
    this.startIndex = startIndex;
    this.endIndex = endIndex;
  }

  public static <T> Iterable<T> of(T[] array) {
    return of(array, /* startIndex */ 0, /* endIndex */ array.length);
  }

  public static <T> Iterable<T> of(final T[] array, final int startIndex, int endIndex) {
    Preconditions.checkPositionIndexes(startIndex, endIndex, array.length);

    // Special-case the empty Iterator with a singleton value.
    if (startIndex >= endIndex) {
      // Note that Collections.emptyIterator().remove() throws an IllegalStateException. We prefer
      // that remove() throws an UnsupportedOperationException for an empty Iterator, so we use
      // ImmutableList instead.
      return ImmutableList.of();
    } else if (endIndex - startIndex == 1) {
      return new Iterable<T>() {
        @Override
        public Iterator<T> iterator() {
          // This always looks up the element in the array in case the user modifies the contents of
          // the array outside of this method. That would probably be a bad thing for the user to
          // do, but this ensures that the behavior is consistent with ArrayIterable.
          return Iterators.singletonIterator(array[startIndex]);
        }
      };
    } else {
      return new ArrayIterable<T>(array, startIndex, endIndex);
    }
  }

  @Override
  public Iterator<T> iterator() {

    return new Iterator<T>() {

      private int index = startIndex;

      @Override
      public boolean hasNext() {
        return index < endIndex;
      }

      @Override
      public T next() {
        if (hasNext()) {
          T element = array[index];
          index++;
          return element;
        } else {
          throw new NoSuchElementException();
        }
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }

    };
  }

}
