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
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import org.immutables.value.Value;

@BuckStylePrehashedValue
public abstract class NativeLinkableCacheKey {

  public static NativeLinkableCacheKey of(
      Flavor flavor, Linker.LinkableDepType type, boolean forceLinkWhole, CxxPlatform cxxPlatform) {
    return ImmutableNativeLinkableCacheKey.of(flavor, type, forceLinkWhole, cxxPlatform);
  }

  public abstract Flavor getFlavor();

  public abstract Linker.LinkableDepType getType();

  public abstract boolean getForceLinkWhole();

  @Value.Auxiliary
  // used only when loading from cache
  public abstract CxxPlatform getCxxPlatform();
}
