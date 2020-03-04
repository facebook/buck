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

package com.facebook.buck.util.collect;

import java.util.Arrays;

/** Utilities for hashtable implementations. */
class Hashtables {

  /** Make a better hash. */
  // this is copy-paste from Guava
  static int smear(int hashCode) {
    int C1 = 0xcc9e2d51;
    int C2 = 0x1b873593;
    return C2 * Integer.rotateLeft(hashCode * C1, 15);
  }

  /**
   * How much should be a hashtable size for given collection size. Returns a power of two length
   * array.
   */
  static int tableSizeForCollectionSize(int collectionSize) {
    if (collectionSize <= 2) {
      return collectionSize;
    }
    int minSize = collectionSize + (collectionSize >> 2);
    return Integer.highestOneBit((minSize - 1) << 1);
  }

  /** Empty object array. */
  static final Object[] EMPTY_ARRAY = new Object[0];
  /** Empty integer array. */
  static final int[] EMPTY_INT_ARRAY = new int[0];
  /** Single zero array. */
  static final int[] INT_ARRAY_OF_0 = new int[] {0};

  static final int[] INT_ARRAY_OF_0_1 = new int[] {0, 1};
  static final int[] INT_ARRAY_OF_1_0 = new int[] {1, 0};

  /** Return the array if it is of given size, otherwise create a copy. */
  @SuppressWarnings("unchecked")
  static <A> A[] copyOfOrUse(A[] array, int size) {
    if (size == 0) {
      return (A[]) EMPTY_ARRAY;
    } else if (array.length == size) {
      return array;
    } else {
      return Arrays.copyOf(array, size);
    }
  }
}
