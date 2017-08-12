// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.regalloc;

import com.android.tools.r8.errors.Unreachable;
import java.util.Arrays;
import java.util.BitSet;

/**
 * Simple mapping from a register to an int value.
 * <p>
 * The backing for the mapping grows as needed up to a given limit. If no mapping exists for
 * a register number the value is assumed to be Integer.MAX_VALUE.
 */

public class RegisterPositions {

  enum Type { MONITOR, CONST_NUMBER, OTHER, ANY }

  private static final int INITIAL_SIZE = 16;
  private int limit;
  private int[] backing;
  private BitSet registerHoldsConstant;
  private BitSet registerHoldsMonitor;

  public RegisterPositions(int limit) {
    this.limit = limit;
    backing = new int[INITIAL_SIZE];
    for (int i = 0; i < INITIAL_SIZE; i++) {
      backing[i] = Integer.MAX_VALUE;
    }
    registerHoldsConstant = new BitSet(limit);
    registerHoldsMonitor = new BitSet(limit);
  }

  public boolean hasType(int index, Type type) {
    switch (type) {
      case MONITOR:
        return holdsMonitor(index);
      case CONST_NUMBER:
        return holdsConstant(index);
      case OTHER:
        return !holdsMonitor(index) && !holdsConstant(index);
      case ANY:
        return true;
      default:
        throw new Unreachable("Unexpected register position type: " + type);
    }
  }

  private boolean holdsConstant(int index) {
    return registerHoldsConstant.get(index);
  }

  private boolean holdsMonitor(int index) { return registerHoldsMonitor.get(index); }

  public void set(int index, int value) {
    if (index >= backing.length) {
      grow(index + 1);
    }
    backing[index] = value;
  }

  public void set(int index, int value, LiveIntervals intervals) {
    set(index, value);
    registerHoldsConstant.set(index, intervals.isConstantNumberInterval());
    registerHoldsMonitor.set(index, intervals.usedInMonitorOperation());
  }

  public int get(int index) {
    if (index < backing.length) {
      return backing[index];
    }
    assert index < limit;
    return Integer.MAX_VALUE;
  }

  public void grow(int minSize) {
    int size = backing.length;
    while (size < minSize) {
      size *= 2;
    }
    size = Math.min(size, limit);
    int oldSize = backing.length;
    backing = Arrays.copyOf(backing, size);
    for (int i = oldSize; i < size; i++) {
      backing[i] = Integer.MAX_VALUE;
    }
  }
}
