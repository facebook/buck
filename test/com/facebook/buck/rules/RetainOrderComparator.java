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

package com.facebook.buck.rules;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Comparator;

/**
 * A utility to create a {@link Comparator} based on a list such that when the elements are
 * compared, the original list order is preserved.
 */
final class RetainOrderComparator {

  /** Utility class: do not instantiate. */
  private RetainOrderComparator() {}

  /**
   * This is meant to be used for testing when a list of objects created via EasyMock need to be
   * added to a SortedSet:
   *
   * <pre>
   * Iterable&lt;T> iterable;
   * Comparator&lt;T> comparator = RetainOrderComparator.createComparator(iterable);
   * ImmutableSortedSet&lt;T> sortedElements = ImmutableSortedSet.copyOf(comparator, iterable);
   * </pre>
   */
  public static <T> Comparator<T> createComparator(Iterable<T> iterable) {
    final ImmutableList<T> items = ImmutableList.copyOf(iterable);
    return (a, b) -> {
      int indexA = -1;
      int indexB = -1;
      int index = 0;
      for (T item : items) {
        // Note that == is used rather than .equals() because this is often used with a list of
        // objects created via EasyMock, which means it would be a pain to mock out all of the
        // calls to .equals(). Fortunately, most lists used during are short, so this is not
        // prohibitively expensive even though it is O(N).
        if (a == item) {
          indexA = index;
        }
        if (b == item) {
          indexB = index;
        }
        ++index;
      }

      Preconditions.checkState(indexA >= 0, "The first element must be in the collection");
      Preconditions.checkState(indexB >= 0, "The second element must be in the collection");

      return indexA - indexB;
    };
  }
}
