// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

/**
 * DexItems of this kind have cached hash values and quick equals check.
 */
public abstract class CachedHashValueDexItem extends DexItem {

  private static final int NOT_COMPUTED_HASH_VALUE = -1;
  private static final int SENTINEL_HASH_VALUE = 0;
  private volatile int hash = NOT_COMPUTED_HASH_VALUE;

  protected abstract int computeHashCode();

  protected abstract boolean computeEquals(Object other);

  @Override
  public final int hashCode() {
    int cache = hash;
    if (cache == NOT_COMPUTED_HASH_VALUE) {
      cache = computeHashCode();
      if (cache == NOT_COMPUTED_HASH_VALUE) {
        cache = SENTINEL_HASH_VALUE;
      }
      hash = cache;
    }
    return cache;
  }

  @Override
  public void flushCachedValues() {
    super.flushCachedValues();
    hash = NOT_COMPUTED_HASH_VALUE;
  }

  @Override
  public final boolean equals(Object other) {
    return this == other || computeEquals(other);
  }
}
