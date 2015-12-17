/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.parser;

import com.google.common.base.Preconditions;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

/**
 * A lightweight wrapper over a {@link ConcurrentMap} that imitates the bits of the public API of
 * {@link com.google.common.cache.Cache} that's used by the {@link Parser}.
 */
class OptimisticLoadingCache<K, V> {

  /**
   * Resizing is expensive.  This saves us four resizes from the normal default of 16.
   */
  static final int DEFAULT_INITIAL_CAPACITY = 256;

  /**
   * Taken from {@link ConcurrentMap}.
   */
  static final float DEFAULT_LOAD_FACTOR = 0.75f;

  private final ConcurrentMap<K, V> values;

  public OptimisticLoadingCache(int parsingThreads) {
    this.values = new ConcurrentHashMap<>(
        DEFAULT_INITIAL_CAPACITY,
        DEFAULT_LOAD_FACTOR,
        parsingThreads);
  }

  public V get(K key, Callable<V> loader) throws ExecutionException {
    V value = values.get(key);
    if (value != null) {
      return value;
    }

    try {
      value = Preconditions.checkNotNull(loader.call());
      V seen = values.putIfAbsent(key, value);
      return seen == null ? value : seen;
    } catch (Exception e) {
      throw new ExecutionException(e);
    }
  }

  @Nullable
  public V getIfPresent(K key) {
    return values.get(key);
  }

  public boolean containsKey(K key) {
    return values.containsKey(key);
  }

  public void invalidateAll(Set<K> keys) {
    for (K key : keys) {
      invalidate(key);
    }
  }

  public void invalidate(K key) {
    values.remove(key);
  }

  public void invalidateAll() {
    smash();
  }

  /**
   * Humorous renaming of {@link java.util.Map#clear()}. "OLC smash!" Geddit? No? Oh well.
   */
  public void smash() {
    values.clear();
  }
}
