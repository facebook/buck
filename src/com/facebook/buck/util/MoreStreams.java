/*
 * Copyright 2016-present Facebook, Inc.
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

import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Nullable;

/**
 * Utility functions for working with Java 8 streams.
 */
public final class MoreStreams {
  private MoreStreams() {}

  /**
   * Returns a function suitable for use in {@link Stream#flatMap(Function)} that casts the input
   * to the given class if possible.
   *
   * Usage: {@code stream.flatMap(filterCast(Foo.class))}.
   *
   * Note, this is slightly slower than simply doing a filter and then explicitly casting.
   * {@code stream.filter(Foo.class::isInstance).map(input -> ((Foo)input).stuff())}.
   *
   * In a benchmark of {@code filterCast -> map -> collect(toList)} with 1000 element input, this
   * was the normalized result:
   * <pre>
   * flatMap(filterCast)  1.728465804
   * filter               1.493715342
   * plain for loop       1
   * </pre>
   *
   * @param cls Class to cast to
   * @param <T> Input type
   * @param <R> Result type
   * @return function that returns a singleton stream of the casted input, or null if input is not
   * an instance.
   */
  @SuppressWarnings("unchecked")
  @Nullable
  public static <T, R> Function<T, Stream<R>> filterCast(Class<R> cls) {
    return input -> {
      if (cls.isInstance(input)) {
        // This is notably faster than Stream.of();
        return new SingletonStream<>((R) input);
      } else {
        // Returning null instead of empty stream is slightly faster. flatMap treats nulls the same
        // as an empty stream.
        return null;
      }
    };
  }
}
