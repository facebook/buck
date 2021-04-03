package net.starlark.java.eval;

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
