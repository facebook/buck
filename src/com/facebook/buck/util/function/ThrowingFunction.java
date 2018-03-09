/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.util.function;

import com.google.common.base.Throwables;
import java.util.function.Function;

/** This version of {@code Function<T, R>} that can throw an exception. */
@FunctionalInterface
public interface ThrowingFunction<T, R, E extends Exception> {
  R apply(T t) throws E;

  /** Returns a Function that will wrap any thrown exception in a RuntimeException. */
  default Function<T, R> asFunction() {
    return t -> {
      try {
        return apply(t);
      } catch (Exception e) {
        Throwables.throwIfUnchecked(e);
        throw new RuntimeException(e);
      }
    };
  }

  /** A simple helper for constructing a Function from a throwing lambda. */
  static <T2, R2, E2 extends Exception> Function<T2, R2> asFunction(
      ThrowingFunction<T2, R2, E2> throwingFunction) {
    return throwingFunction.asFunction();
  }
}
