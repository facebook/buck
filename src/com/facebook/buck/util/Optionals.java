/*
 * Copyright 2012-present Facebook, Inc.
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.function.Function;

public class Optionals {

  /** Utility class: do not instantiate. */
  private Optionals() {}

  public static <T> void addIfPresent(
      Optional<T> optional, ImmutableCollection.Builder<T> collection) {
    if (optional.isPresent()) {
      collection.add(optional.get());
    }
  }

  public static <K, T> void putIfPresent(
      Optional<T> optional, K key, ImmutableMap.Builder<K, T> collection) {
    if (optional.isPresent()) {
      collection.put(key, optional.get());
    }
  }

  public static <T, U> Optional<U> bind(
      Optional<? extends T> optional, Function<? super T, Optional<U>> f) {
    if (!optional.isPresent()) {
      return Optional.empty();
    }
    return f.apply(optional.get());
  }

  public static <T extends Comparable<T>> int compare(Optional<T> first, Optional<T> second) {
    if (first.isPresent() && !second.isPresent()) {
      return +1;
    } else if (!first.isPresent() && second.isPresent()) {
      return -1;
    } else if (!first.isPresent() && !second.isPresent()) {
      return 0;
    } else {
      return first.get().compareTo(second.get());
    }
  }

  public static <T> T require(Optional<T> optional) {
    Preconditions.checkState(optional.isPresent());
    return optional.get();
  }

  /**
   * Returns a singleton stream of an {@code Optional}'s value if present, otherwise an empty
   * stream.
   *
   * <p>Useful for filtering present instances in a stream pipeline:
   *
   * <pre>{@code
   * Stream.of(Optional.empty(), Optional.of(1), Optional.of(2))
   *   .flatMap(Optionals::toStream)
   *
   * // Yields a stream of 2 elements, [1, 2]
   * }</pre>
   */
  public static <T> RichStream<T> toStream(Optional<T> optional) {
    if (optional.isPresent()) {
      return RichStream.of(optional.get());
    } else {
      return RichStream.empty();
    }
  }

  private static final Optional<Boolean> OPTIONAL_TRUE = Optional.of(true);
  private static final Optional<Boolean> OPTIONAL_FALSE = Optional.of(false);

  public static Optional<Boolean> ofBoolean(boolean b) {
    return b ? OPTIONAL_TRUE : OPTIONAL_FALSE;
  }
}
