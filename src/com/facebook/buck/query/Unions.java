/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.query;

import com.facebook.buck.core.model.QueryTarget;
import com.google.common.collect.Iterables;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Specialized utility for creating unions of sets in the <code>com.facebook.buck.query</code>
 * package. This should not be made generally available in the Buck codebase because the {@code
 * throws QueryException} part of the signature of {@link SourceToSetFunction#toSet(Object)} is
 * specific to this package, which is why this is package-private.
 */
class Unions {
  private Unions() {}

  /** Input to set mapper for {@link #of(SourceToSetFunction, Collection)}. */
  @FunctionalInterface
  interface SourceToSetFunction<I, T> {
    Set<T> toSet(I source) throws QueryException;
  }

  /**
   * Takes a mapping function <code>f</code> and an {@link Iterable} of sources and produces a set
   * that is the union of <code>f</code> applied to each of the sources.
   *
   * @param f function that produces a {@link java.util.Set} that can be assumed to be immutable.
   * @param sources inputs to pass to <code>f</code>
   */
  static <I, T extends QueryTarget> Set<T> of(SourceToSetFunction<I, T> f, Collection<I> sources)
      throws QueryException {
    int size = sources.size();
    if (size == 0) {
      return Collections.emptySet();
    } else if (size == 1) {
      return f.toSet(Iterables.getOnlyElement(sources));
    } else {
      Set<T> out = new HashSet<>();
      for (I source : sources) {
        out.addAll(f.toSet(source));
      }
      return out;
    }
  }
}
