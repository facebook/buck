/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.model;

import com.google.common.collect.ComparisonChain;

import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.Objects;

/**
 * Simple type representing a pair of differently typed values.
 *
 * Used to represent pair-like structures in coerced values.
 */
public class Pair<FIRST, SECOND> {

  private FIRST first;
  private SECOND second;
  private WeakReference<Integer> hashCache = null;
  private WeakReference<String> stringCache = null;

  public Pair(FIRST first, SECOND second) {
    this.first = first;
    this.second = second;
  }

  public FIRST getFirst() {
    return first;
  }

  public SECOND getSecond() {
    return second;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Pair)) {
      return false;
    }

    Pair<?, ?> that = (Pair<?, ?>) other;
    return Objects.equals(this.first, that.first) &&
        Objects.equals(this.second, that.second);
  }

  @Override
  public int hashCode() {
    synchronized (this) {
      if (hashCache == null) {
        return calculateHashAndCache();
      }
      Integer hash = hashCache.get();
      if (hash == null) {
        return calculateHashAndCache();
      }
      return hash;
    }
  }

  private int calculateHashAndCache() {
    int hash = Objects.hash(first, second);
    hashCache = new WeakReference<>(hash);
    return hash;
  }

  @Override
  public String toString() {
    synchronized (this) {
      if (stringCache == null) {
        return createStringAndCache();
      }
      String string = stringCache.get();
      if (string == null) {
        return createStringAndCache();
      }
      return string;
    }
  }

  private String createStringAndCache() {
    String string = String.format("Pair(%s, %s)", first, second);
    stringCache = new WeakReference<>(string);
    return string;
  }

  /**
   * Provides a comparison using the natural ordering of contained types (which my be comparable).
   */
  public static <FIRST extends Comparable<FIRST>, SECOND extends Comparable<SECOND>>
      Comparator<Pair<FIRST, SECOND>> comparator() {
    return new Comparator<Pair<FIRST, SECOND>>() {
      @Override
      public int compare(Pair<FIRST, SECOND> o1, Pair<FIRST, SECOND> o2) {
        if (o1 == o2) {
          return 0;
        }

        return ComparisonChain.start()
            .compare(o1.first, o2.first)
            .compare(o1.second, o2.second)
            .result();
      }
    };
  }
}
