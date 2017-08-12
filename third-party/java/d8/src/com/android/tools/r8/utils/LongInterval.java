// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

/**
 * Closed interval of two longs.
 */
public class LongInterval {

  private final long min;
  private final long max;

  public LongInterval(int min, int max) {
    assert min <= max;
    this.min = min;
    this.max = max;
  }

  public LongInterval(long min, long max) {
    assert min <= max;
    this.min = min;
    this.max = max;
  }

  public long getMin() {
    return min;
  }

  public long getMax() {
    return max;
  }

  public boolean isSingleValue() {
    return min == max;
  }

  public long getSingleValue() {
    assert isSingleValue();
    return min;
  }

  public boolean containsValue(long value) {
    return min <= value && value <= max;
  }

  public boolean doesntOverlapWith(LongInterval other) {
    return other.max < min || max < other.min;
  }

  public boolean overlapsWith(LongInterval other) {
    return other.max >= min && max >= other.min;
  }

  @Override
  public String toString() {
    return "[" + min + ", " + max + "]";
  }
}
