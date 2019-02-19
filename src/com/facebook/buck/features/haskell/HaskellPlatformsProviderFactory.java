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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import java.util.Optional;

public class HaskellPlatformsProviderFactory implements ToolchainFactory<HaskellPlatformsProvider> {

  @Override
  public Optional<HaskellPlatformsProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    CxxPlatformsProvider cxxPlatformsProviderFactory =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProviderFactory.getUnresolvedCxxPlatforms();
    UnresolvedCxxPlatform defaultCxxPlatform =
        cxxPlatformsProviderFactory.getDefaultUnresolvedCxxPlatform();

    HaskellPlatformsFactory haskellPlatformsFactory =
        new HaskellPlatformsFactory(context.getBuckConfig(), context.getExecutableFinder());

    FlavorDomain<HaskellPlatform> haskellPlatforms =
        haskellPlatformsFactory.getPlatforms(cxxPlatforms);
    HaskellPlatform defaultHaskellPlatform =
        haskellPlatforms.getValue(defaultCxxPlatform.getFlavor());

    return Optional.of(HaskellPlatformsProvider.of(defaultHaskellPlatform, haskellPlatforms));
  }
}
