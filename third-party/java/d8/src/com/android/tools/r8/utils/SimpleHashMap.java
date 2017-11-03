// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

abstract class SimpleHashMap {

  private int size = 0;  // number of elements in this hash map.
  private int limit;  // resize when size reaches limit.
  private int mask;  // length - 1.

  // Constants for capacity.
  static final int MIN_CAPACITY = 2;
  static final int DEFAULT_CAPACITY = 50;

  // Constant for loadfactor.
  static final double MIN_LOAD_FACTOR = 0.2;
  static final double MAX_LOAD_FACTOR = 0.8;
  static final double DEFAULT_LOAD_FACTOR = 0.6;

  SimpleHashMap() {
    this(DEFAULT_CAPACITY);
  }

  SimpleHashMap(int initialCapacity) {
    this(initialCapacity, DEFAULT_LOAD_FACTOR);
  }

  SimpleHashMap(int initialCapacity, double loadFactor) {
    initialCapacity = Math.max(MIN_CAPACITY, initialCapacity);
    loadFactor = Math.min(MAX_LOAD_FACTOR, Math.max(MIN_LOAD_FACTOR, loadFactor));
    final int initialLength = roundToPow2((int) ((double) initialCapacity / loadFactor));
    initialize(initialLength, (int) ((double) initialLength * loadFactor));
  }

  public int size() {
    return size;
  }

  @Override
  public String toString() {
    return this.getClass().getName() + ", " + size + "(length " + length() + ")";
  }

  int length() {
    return mask + 1;
  }

  void initialize(final int length, final int limit) {
    size = 0;
    mask = length - 1;
    this.limit = limit;
  }

  void resize() {
    // Double length and limit.
    initialize(length() << 1, limit << 1);
  }

  void ensureCapacity() {
    if (size >= limit) {
      resize();
    }
  }

  void incrementSize() {
    size++;
  }

  private int roundToPow2(int number) {
    number--;
    number |= number >> 1;
    number |= number >> 2;
    number |= number >> 4;
    number |= number >> 8;
    number |= number >> 16;
    return number + 1;
  }

  int firstProbe(final int hash) {
    return hash & mask;
  }

  int nextProbe(final int last, final int index) {
    return (last + index) & mask;
  }
}
