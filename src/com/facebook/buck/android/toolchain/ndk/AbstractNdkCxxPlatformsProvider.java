/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android.toolchain.ndk;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsSupplier;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.toolchain.BaseToolchain;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

/** Provides all {@link NdkCxxPlatform}s by {@link TargetCpuType}. */
@Value.Immutable(copy = false, builder = false)
@BuckStyleImmutable
public abstract class AbstractNdkCxxPlatformsProvider extends BaseToolchain
    implements CxxPlatformsSupplier {
  public static final String DEFAULT_NAME = "ndk-cxx-platforms";

  /** @return all {@link NdkCxxPlatform}s by {@link TargetCpuType}. */
  @Value.Parameter
  public abstract ImmutableMap<TargetCpuType, NdkCxxPlatform> getNdkCxxPlatforms();

  /** @return {@link CxxPlatform} of all {@link NdkCxxPlatform}s */
  @Override
  public ImmutableMap<Flavor, CxxPlatform> getCxxPlatforms() {
    ImmutableMap.Builder<Flavor, CxxPlatform> cxxSystemPlatformsBuilder = ImmutableMap.builder();

    for (NdkCxxPlatform ndkCxxPlatform : getNdkCxxPlatforms().values()) {
      cxxSystemPlatformsBuilder.put(
          ndkCxxPlatform.getCxxPlatform().getFlavor(), ndkCxxPlatform.getCxxPlatform());
    }
    return cxxSystemPlatformsBuilder.build();
  }
}
