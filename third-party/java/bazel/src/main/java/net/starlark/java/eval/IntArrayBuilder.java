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

  public void add(int value) {
    if (size == array.length) {
      array = Arrays.copyOf(array, Math.max(10, array.length * 2));
    }
    array[size++] = value;
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
}
