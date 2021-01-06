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

package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** A simple cache for helping implement {@link NativeLinkableGroup}. */
public class PlatformMappedCache<T> {
  // Use the Flavor as a key because hashing the CxxPlatform is expensive.
  private final Cache<Flavor, T> cache = CacheBuilder.newBuilder().build();

  /** Returns either a cached or computed linkable. */
  public T get(CxxPlatform cxxPlatform, Supplier<T> supplier) {
    try {
      return cache.get(cxxPlatform.getFlavor(), supplier::get);
    } catch (ExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw new UncheckedExecutionException(e.getCause());
    }
  }
}
