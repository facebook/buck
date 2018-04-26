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

package com.facebook.buck.apple.toolchain;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsSupplier;
import com.google.common.collect.ImmutableMap;
import org.immutables.value.Value;

@Value.Immutable(copy = false, builder = false)
@BuckStyleImmutable
public abstract class AbstractAppleCxxPlatformsProvider implements CxxPlatformsSupplier {

  public static final String DEFAULT_NAME = "apple-cxx-platforms";

  @Value.Parameter
  public abstract FlavorDomain<AppleCxxPlatform> getAppleCxxPlatforms();

  /** @return {@link CxxPlatform} of all {@link AppleCxxPlatform}s */
  @Override
  public ImmutableMap<Flavor, CxxPlatform> getCxxPlatforms() {
    ImmutableMap.Builder<Flavor, CxxPlatform> cxxSystemPlatformsBuilder = ImmutableMap.builder();

    for (AppleCxxPlatform appleCxxPlatform : getAppleCxxPlatforms().getValues()) {
      cxxSystemPlatformsBuilder.put(
          appleCxxPlatform.getCxxPlatform().getFlavor(), appleCxxPlatform.getCxxPlatform());
    }
    return cxxSystemPlatformsBuilder.build();
  }
}
