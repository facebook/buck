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

package com.facebook.buck.swift.toolchain.impl;

import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.swift.toolchain.UnresolvedSwiftPlatform;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

public class SwiftPlatformsProviderFactory implements ToolchainFactory<SwiftPlatformsProvider> {

  @Override
  public Optional<SwiftPlatformsProvider> createToolchain(
      ToolchainProvider toolchainProvider,
      ToolchainCreationContext context,
      TargetConfiguration toolchainTargetConfiguration) {
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            AppleCxxPlatformsProvider.class);

    return Optional.of(SwiftPlatformsProvider.of(createSwiftPlatforms(appleCxxPlatformsProvider)));
  }

  private static FlavorDomain<UnresolvedSwiftPlatform> createSwiftPlatforms(
      AppleCxxPlatformsProvider appleCxxPlatformsProvider) {

    FlavorDomain<UnresolvedAppleCxxPlatform> appleCxxPlatforms =
        appleCxxPlatformsProvider.getUnresolvedAppleCxxPlatforms();

    ImmutableMap.Builder<Flavor, UnresolvedSwiftPlatform> swiftPlatforms = ImmutableMap.builder();
    for (Flavor flavor : appleCxxPlatforms.getFlavors()) {
      swiftPlatforms.put(flavor, appleCxxPlatforms.getValue(flavor).getUnresolvedSwiftPlatform());
    }

    return new FlavorDomain<>("Swift Platform", swiftPlatforms.build());
  }
}
