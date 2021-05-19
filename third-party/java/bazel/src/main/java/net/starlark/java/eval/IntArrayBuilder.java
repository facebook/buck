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

package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import java.util.Arrays;

/** Utility to build int[]. */
class IntArrayBuilder {
  private int[] array;
  private int size;

  public IntArrayBuilder() {
    array = ArraysForStarlark.EMPTY_INT_ARRAY;
    size = 0;
  }

  public IntArrayBuilder(int capacity) {
    array = ArraysForStarlark.newIntArray(capacity);
    size = 0;
  }

  public void add(int value) {
    if (size == array.length) {
      array = Arrays.copyOf(array, Math.max(10, array.length * 2));
    }
    array[size++] = value;
  }

  public void addAll(int[] values) {
    if (size + values.length > array.length) {
      int newCapacity = Math.max(size + values.length, array.length * 2);
      array = Arrays.copyOf(array, newCapacity);
    }
    System.arraycopy(values, 0, array, size, values.length);
    size += values.length;
  }

  public int size() {
    return size;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  public int get(int i) {
    Preconditions.checkArgument(i < size);
    return array[i];
  }

  public void pop() {
    Preconditions.checkState(size > 0);
    --size;
  }

  public int[] buildArray() {
    int[] result;
    if (size == array.length) {
      result = array;
    } else {
      result = Arrays.copyOf(array, size);
    }
    array = null; // safety
    return result;
  }

  @Override
  public String toString() {
    StringBuilder r = new StringBuilder();
    r.append("[");
    for (int i = 0; i != size; ++i) {
      if (i != 0) {
        r.append(", ");
      }
      r.append(get(i));
    }
    r.append("]");
    return r.toString();
  }

  public void clear() {
    size = 0;
  }
}
