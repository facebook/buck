package net.starlark.java.eval;

/** Utilities work with Java arrays. */
class ArraysForStarlark {

  private ArraysForStarlark() {
  }

  static final String[] EMPTY_STRING_ARRAY = {};
  static final Object[] EMPTY_OBJECT_ARRAY = {};
  static final int[] EMPTY_INT_ARRAY = {};

  /** Create an object array, or return a default instance when zero length requested. */
  static Object[] newObjectArray(int length) {
    return length != 0 ? new Object[length] : EMPTY_OBJECT_ARRAY;
  }
}
