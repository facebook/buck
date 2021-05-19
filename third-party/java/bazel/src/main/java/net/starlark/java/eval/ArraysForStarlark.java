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

/** Utilities work with Java arrays. */
class ArraysForStarlark {

  private ArraysForStarlark() {}

  static final String[] EMPTY_STRING_ARRAY = {};
  static final Object[] EMPTY_OBJECT_ARRAY = {};
  static final int[] EMPTY_INT_ARRAY = {};

  /** Create an object array, or return a default instance when zero length requested. */
  static Object[] newObjectArray(int length) {
    return length != 0 ? new Object[length] : EMPTY_OBJECT_ARRAY;
  }

  static String[] newStringArray(int length) {
    return length != 0 ? new String[length] : EMPTY_STRING_ARRAY;
  }

  static int[] newIntArray(int length) {
    return length != 0 ? new int[length] : EMPTY_INT_ARRAY;
  }
}
