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

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;

public class Optionals {

  /** Utility class: do not instantiate. */
  private Optionals() {}

  public static <T> void addIfPresent(Optional<T> optional,
                                      ImmutableCollection.Builder<T> collection) {
    if (optional.isPresent()) {
      collection.add(optional.get());
    }
  }

  public static <K, T> void putIfPresent(Optional<T> optional,
                                         K key,
                                         ImmutableMap.Builder<K, T> collection) {
    if (optional.isPresent()) {
      collection.put(key, optional.get());
    }
  }

  public static <T, U> Optional<U> bind(
      Optional<? extends T> optional,
      Function<? super T, Optional<U>> f) {
    if (!optional.isPresent()) {
      return Optional.absent();
    }
    return f.apply(optional.get());
  }

}
