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

package com.facebook.buck.swift.toolchain.impl;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

public class SwiftPlatformsProviderFactory implements ToolchainFactory<SwiftPlatformsProvider> {

  @Override
  public Optional<SwiftPlatformsProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME, AppleCxxPlatformsProvider.class);

    return Optional.of(SwiftPlatformsProvider.of(createSwiftPlatforms(appleCxxPlatformsProvider)));
  }

  private static FlavorDomain<SwiftPlatform> createSwiftPlatforms(
      AppleCxxPlatformsProvider appleCxxPlatformsProvider) {

    FlavorDomain<AppleCxxPlatform> appleCxxPlatforms =
        appleCxxPlatformsProvider.getAppleCxxPlatforms();

    ImmutableMap.Builder<Flavor, SwiftPlatform> swiftPlatforms = ImmutableMap.builder();
    for (Flavor flavor : appleCxxPlatforms.getFlavors()) {
      appleCxxPlatforms
          .getValue(flavor)
          .getSwiftPlatform()
          .ifPresent(swiftPlatform -> swiftPlatforms.put(flavor, swiftPlatform));
    }

    return new FlavorDomain<>("Swift Platform", swiftPlatforms.build());
  }
}
