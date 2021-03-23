// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.eval;

import static java.lang.Math.min;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A Sequence is a finite iterable sequence of Starlark values, such as a list or tuple.
 *
 * <p>Sequences implement the read-only operations of the {@link List} interface, but not its update
 * operations, similar to {@code ImmutableList}. The specification of {@code List} governs how such
 * methods behave and in particular how they report errors. Subclasses of sequence may define ad-hoc
 * mutator methods, such as {@link StarlarkList#extend}, exposed to Starlark, or Java, or both.
 *
 * <p>In principle, subclasses of Sequence could also define the standard update operations of List,
 * but there appears to be little demand, and doing so carries some risk of obscuring unintended
 * mutations to Starlark values that would currently cause the program to crash.
 */
public abstract class Sequence<E> extends StarlarkIndexable<E> implements RandomAccess {

  public abstract int size();
  public abstract E get(int index);

  private static final Object[] EMPTY_ARRAY = {};

  public Object[] toArray() {
    int size = size();
    Object[] array = size != 0 ? new Object[size] : EMPTY_ARRAY;
    for (int i = 0; i < size; ++i) {
      array[i] = get(i);
    }
    return array;
  }

  public boolean contains(Object value) {
    if (value == null) {
      return false;
    }

    int size = size();
    for (int i = 0; i < size; ++i) {
      if (value.equals(get(i))) {
        return true;
      }
    }
    return false;
  }

  public boolean isEmpty() {
    return size() == 0;
  }

  @Override
  public boolean truth() {
    return !isEmpty();
  }

  /** Returns an ImmutableList object with the current underlying contents of this Sequence. */
  public ImmutableList<E> getImmutableList() {
    return ImmutableList.copyOf(this);
  }

  /** Retrieves an entry from a Sequence. */
  @Override
  public E getIndex(StarlarkSemantics semantics, Object key) throws EvalException {
    int index = Starlark.toInt(key, "sequence index");
    return get(EvalUtils.getSequenceIndex(index, size()));
  }

  @Override
  public boolean containsKey(StarlarkSemantics semantics, Object key) throws EvalException {
    return contains((E) key);
  }

  /**
   * Compares two sequences of values. Sequences compare equal if corresponding elements compare
   * equal using {@code x[i] == y[i]}. Otherwise, the result is the ordered comparison of the first
   * element for which {@code x[i] != y[i]}. If one list is a prefix of another, the result is the
   * comparison of the list's sizes.
   *
   * @throws ClassCastException if any comparison failed.
   */
  static int compare(Sequence<?> x, Sequence<?> y) {
    for (int i = 0; i < min(x.size(), y.size()); i++) {
      Object xelem = x.get(i);
      Object yelem = y.get(i);

      // First test for equality. This avoids an unnecessary
      // ordered comparison, which may be unsupported despite
      // the values being equal. Also, it is potentially more
      // expensive. For example, list==list need not look at
      // the elements if the lengths are unequal.
      if (xelem == yelem || xelem.equals(yelem)) {
        continue;
      }

      // The ordered comparison of unequal elements should
      // always be nonzero unless compareTo is inconsistent.
      int cmp = Starlark.compareUnchecked(xelem, yelem);
      if (cmp == 0) {
        throw new IllegalStateException(
            String.format(
                "x.equals(y) yet x.compareTo(y)==%d (x: %s, y: %s)",
                cmp, Starlark.type(xelem), Starlark.type(yelem)));
      }
      return cmp;
    }
    return Integer.compare(x.size(), y.size());
  }

  /**
   * Returns the slice of this sequence, {@code this[start:stop:step]}. <br>
   * For positive strides ({@code step > 0}), {@code 0 <= start <= stop <= size()}. <br>
   * For negative strides ({@code step < 0}), {@code -1 <= stop <= start < size()}. <br>
   * The caller must ensure that the start and stop indices are valid and that step is non-zero.
   */
  public abstract Sequence<E> getSlice(Mutability mu, int start, int stop, int step);

  /**
   * Casts a non-null Starlark value {@code x} to a {@code Sequence<T>}, after checking that each
   * element is an instance of {@code elemType}. On error, it throws an EvalException whose message
   * includes {@code what}, ideally a string literal, as a description of the role of {@code x}.
   */
  public static <T> Sequence<T> cast(Object x, Class<T> elemType, String what)
      throws EvalException {
    Preconditions.checkNotNull(x);
    if (!(x instanceof Sequence)) {
      throw Starlark.errorf("for %s, got %s, want sequence", what, Starlark.type(x));
    }
    int i = 0;
    for (Object elem : (Sequence) x) {
      if (!elemType.isAssignableFrom(elem.getClass())) {
        throw Starlark.errorf(
            "at index %d of %s, got element of type %s, want %s",
            i, what, Starlark.type(elem), Starlark.classType(elemType));
      }
    }
    @SuppressWarnings("unchecked") // safe
    Sequence<T> result = (Sequence) x;
    return result;
  }

  /** Like {@link #cast}, but if x is None, returns an immutable empty list. */
  public static <T> Sequence<T> noneableCast(Object x, Class<T> type, String what)
      throws EvalException {
    return x == Starlark.NONE ? StarlarkList.empty() : cast(x, type, what);
  }

  @Override
  public Iterator<E> iterator() {
    return new Iterator<E>() {
      final int size = size();
      int i = 0;
      @Override
      public boolean hasNext() {
        return i < size;
      }

      @Override
      public E next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return get(i++);
      }
    };
  }

  @Override
  public Spliterator<E> spliterator() {
    return Spliterators.spliterator(this.iterator(), size(), 0);
  }

  public Stream<E> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  private class AsList extends AbstractList<E> {
    @Override
    public E get(int index) {
      return Sequence.this.get(index);
    }

    @Override
    public int size() {
      return Sequence.this.size();
    }

    @Override
    public boolean contains(Object o) {
      return Sequence.this.contains(o);
    }

    @Override
    public Iterator<E> iterator() {
      return Sequence.this.iterator();
    }

    @Override
    public String toString() {
      return Sequence.this.toString();
    }

    @Override
    public Stream<E> stream() {
      return Sequence.this.stream();
    }
  }

  /** View as list. */
  public List<E> asList() {
    return new AsList();
  }
}
