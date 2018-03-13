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

package com.facebook.buck.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An iterable that uses an iterator to traverse through all causes of given {@code throwable} by
 * recursively calling getCause() function. Iterator stops if loop is detected. It also traverses
 * through {@code throwable} itself.
 */
public class ThrowableCauseIterable implements Iterable<Throwable> {

  private Throwable root;

  private ThrowableCauseIterable(@Nullable Throwable throwable) {
    root = throwable;
  }

  /** Initialize {@code ThrowableCauseIterable} with top-level {@code throwable} */
  public static ThrowableCauseIterable of(@Nullable Throwable throwable) {
    return new ThrowableCauseIterable(throwable);
  }

  @Override
  public Iterator<Throwable> iterator() {
    return new ThrowableCauseIterator(root);
  }

  /**
   * An iterator that traverses through all causes of given {@code throwable} by recursively calling
   * getCause() function. Iterator stops if loop is detected. It also traverses through {@code
   * throwable} itself.
   */
  private class ThrowableCauseIterator implements Iterator<Throwable> {
    @Nullable private Throwable next;
    private Set<Throwable> seen =
        Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());

    /** Initialize {@code ThrowableCauseIterator} with top-level {@code throwable} */
    public ThrowableCauseIterator(@Nullable Throwable throwable) {
      next = throwable;
      if (next != null) {
        seen.add(next);
      }
    }

    @Override
    public boolean hasNext() {
      return next != null;
    }

    @Override
    public Throwable next() {
      if (next == null) {
        // by the contract of Java Iterator
        throw new NoSuchElementException();
      }

      Throwable result = next;

      Throwable cause = result.getCause();
      if (cause != null && seen.contains(cause)) {
        // if loop is detected, stop iteration
        cause = null;
      } else {
        seen.add(cause);
      }

      next = cause;

      return result;
    }
  }
}
