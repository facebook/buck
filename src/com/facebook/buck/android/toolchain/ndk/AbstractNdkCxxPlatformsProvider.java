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

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.BaseToolchain;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsSupplier;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Objects;
import org.immutables.value.Value;

/** Provides all {@link NdkCxxPlatform}s by {@link TargetCpuType}. */
@Value.Immutable(copy = false, builder = false)
@BuckStyleImmutable
public abstract class AbstractNdkCxxPlatformsProvider extends BaseToolchain
    implements CxxPlatformsSupplier {
  public static final String DEFAULT_NAME = "ndk-cxx-platforms";

  /** @return all {@link UnresolvedNdkCxxPlatform}s by {@link TargetCpuType}. */
  @Value.Parameter
  public abstract ImmutableMap<TargetCpuType, UnresolvedNdkCxxPlatform> getNdkCxxPlatforms();

  /** @return all resolved {@link NdkCxxPlatform}s by {@link TargetCpuType}. */
  public ImmutableMap<TargetCpuType, NdkCxxPlatform> getResolvedNdkCxxPlatforms(
      BuildRuleResolver resolver) {
    return ImmutableMap.copyOf(
        Maps.transformValues(
            getNdkCxxPlatforms(), platform -> Objects.requireNonNull(platform).resolve(resolver)));
  }

  /** @return {@link CxxPlatform} of all {@link NdkCxxPlatform}s */
  @Override
  public ImmutableMap<Flavor, UnresolvedCxxPlatform> getCxxPlatforms() {
    ImmutableMap.Builder<Flavor, UnresolvedCxxPlatform> cxxSystemPlatformsBuilder =
        ImmutableMap.builder();

    for (UnresolvedNdkCxxPlatform ndkCxxPlatform : getNdkCxxPlatforms().values()) {
      cxxSystemPlatformsBuilder.put(
          ndkCxxPlatform.getCxxPlatform().getFlavor(), ndkCxxPlatform.getCxxPlatform());
    }
    return cxxSystemPlatformsBuilder.build();
  }

  @Override
  public String getName() {
    return DEFAULT_NAME;
  }
}
