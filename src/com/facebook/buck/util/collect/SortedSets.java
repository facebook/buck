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

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import com.google.common.collect.Ordering;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import javax.annotation.Nullable;

public class SortedSets {

  private SortedSets() {}

  /**
   * A view merging the two underlying sorted sets.
   *
   * <p>This View performs all operations lazily, and sacrifices CPU to reduce memory overhead. If
   * operations are being repeatedly performed, it may be better to perform eager copies using
   * something like {@link com.google.common.collect.ImmutableSortedSet#copyOf}.
   *
   * <p>The behavior of this view is unspecified if the underlying SortedSets are modified after
   * construction.
   */
  public static <T extends Comparable<T>> SortedSet<T> union(SortedSet<T> a, SortedSet<T> b) {
    return new MergedSortedSetView<T>(a, b);
  }

  static class MergedSortedSetView<T extends Comparable<T>> implements SortedSet<T> {

    private final SortedSet<T> a;
    private final SortedSet<T> b;
    private final Comparator<? super T> comparator;

    public MergedSortedSetView(SortedSet<T> a, SortedSet<T> b) {
      Preconditions.checkArgument(
          areSameComparators(a.comparator(), b.comparator()),
          "Cannot merge SortedSets with different comparators, got: %s, %s",
          a.comparator(),
          b.comparator());
      this.a = a;
      this.b = b;

      this.comparator = a.comparator() == null ? Ordering.natural() : a.comparator();
    }

    /**
     * Produce an Iterator which merges the underlying SortedSets in a sorted fashion,
     * de-duplicating entries based on their equality-semantics.
     *
     * <p>The iterated order of values which are not equal but are equivalent according to the
     * Comparator is unspecified, but stable for a given instance of MergedSortedSet.
     *
     * <p>The return Iterator is not threadsafe.
     */
    @Override
    public Iterator<T> iterator() {
      PeekingIterator<T> left = Iterators.peekingIterator(a.iterator());
      PeekingIterator<T> right = Iterators.peekingIterator(b.iterator());
      return new Iterator<T>() {

        @Override
        public boolean hasNext() {
          return left.hasNext() || right.hasNext();
        }

        @Override
        public T next() {
          if (!left.hasNext()) {
            return right.next();
          } else if (!right.hasNext()) {
            return left.next();
          } else {
            T lval = left.peek();
            T rval = right.peek();
            if (lval.equals(rval)) {
              right.next();
              return left.next();
            } else {
              return comparator.compare(lval, rval) > 0 ? right.next() : left.next();
            }
          }
        }
      };
    }

    /**
     * Returns the Comparator used to order the elements in this set.
     *
     * <p>Note that, like ImmutableSortedSet, this returns Ordering.natural() rather than null when
     * the natural ordering is used, in violation of the SortedSet contract.
     */
    @Override
    @Nullable
    public Comparator<? super T> comparator() {
      return comparator;
    }

    @Override
    public SortedSet<T> subSet(T fromElement, T toElement) {
      return new MergedSortedSetView<>(
          a.subSet(fromElement, toElement), b.subSet(fromElement, toElement));
    }

    @Override
    public SortedSet<T> headSet(T toElement) {
      return new MergedSortedSetView<>(a.headSet(toElement), b.headSet(toElement));
    }

    @Override
    public SortedSet<T> tailSet(T fromElement) {
      return new MergedSortedSetView<>(a.tailSet(fromElement), b.tailSet(fromElement));
    }

    @Override
    public T first() {
      return iterator().next();
    }

    @Override
    public T last() {
      if (a.isEmpty()) {
        return b.last();
      }
      if (b.isEmpty()) {
        return a.last();
      }
      T a = this.a.last();
      T b = this.b.last();
      return comparator.compare(a, b) >= 0 ? a : b;
    }

    /**
     * Return the number of de-duplicated elements in the underlying sets.
     *
     * <p>Note that this is relatively expensive to compute, and {@link #sizeEstimate} may be more
     * appropriate.
     */
    @Override
    public int size() {
      return Sets.union(a, b).size();
    }

    /**
     * Return an estimate of the size of this Set, which is quicker to compute than the actual size.
     *
     * <p>This will always return an over-estimate rather than an under-estimate.
     */
    public int sizeEstimate() {
      return a.size() + b.size();
    }

    @Override
    public boolean isEmpty() {
      return a.isEmpty() && b.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
      return a.contains(o) || b.contains(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
      return c.stream().allMatch(e -> contains(e));
    }

    @Override
    public Object[] toArray() {
      return Iterators.toArray(iterator(), Object.class);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T1> T1[] toArray(T1[] a) {
      Iterator<T> iterator = iterator();

      T1[] destination = a;
      int size = size();
      if (size > a.length) {
        destination = (T1[]) new Object[size];
      }
      for (int i = 0; i < size; ++i) {
        destination[i] = (T1) iterator.next();
      }
      while (destination.length > size) {
        destination[size++] = null;
      }
      return destination;
    }

    @Override
    public String toString() {
      return "[" + Joiner.on(", ").join(iterator()) + "]";
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (!(obj instanceof Set)) {
        return false;
      }
      if (obj instanceof MergedSortedSetView) {
        MergedSortedSetView<T> other = (MergedSortedSetView<T>) obj;
        if (this.a == other.a && this.b == other.b) {
          return true;
        }
      }
      Set<T> other = (Set<T>) obj;
      return size() == other.size() && other.containsAll(this);
    }

    @Override
    public int hashCode() {
      int hashCode = 0;
      for (T e : this) {
        hashCode += e != null ? e.hashCode() : 0;
      }
      return hashCode;
    }

    @Override
    public boolean add(T t) {
      throw new UnsupportedOperationException("Cannot modify MergedSortedSetView");
    }

    @Override
    public boolean remove(Object o) {
      throw new UnsupportedOperationException("Cannot modify MergedSortedSetView");
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
      throw new UnsupportedOperationException("Cannot modify MergedSortedSetView");
    }

    @Override
    public boolean retainAll(Collection<?> c) {
      throw new UnsupportedOperationException("Cannot modify MergedSortedSetView");
    }

    @Override
    public boolean removeAll(Collection<?> c) {
      throw new UnsupportedOperationException("Cannot modify MergedSortedSetView");
    }

    @Override
    public void clear() {
      throw new UnsupportedOperationException("Cannot modify MergedSortedSetView");
    }
  }

  /**
   * Comparators which are all equivalents and sort things by natural order.
   *
   * <p>A HashSet rather than ImmutableSet because it contains null, which is forbidden in
   * ImmutableSets.
   */
  private static final Set<Comparator<?>> NATURAL_COMPARATORS =
      new HashSet<Comparator<?>>() {
        {
          add(Comparator.naturalOrder());
          add(Ordering.natural());
          add(null);
        }
      };

  private static boolean areSameComparators(Comparator<?> left, Comparator<?> right) {
    if (Objects.equals(left, right)) {
      return true;
    }
    return NATURAL_COMPARATORS.contains(left) && NATURAL_COMPARATORS.contains(right);
  }

  public static <T> int sizeEstimate(SortedSet<T> set) {
    if (set instanceof MergedSortedSetView) {
      return ((MergedSortedSetView<?>) set).sizeEstimate();
    }
    return set.size();
  }
}
