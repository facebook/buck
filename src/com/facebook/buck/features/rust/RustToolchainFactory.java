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

package com.facebook.buck.features.rust;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.RichStream;
import java.util.Optional;

/** Constructs {@link RustToolchain}s. */
public class RustToolchainFactory implements ToolchainFactory<RustToolchain> {

  private static final String SECTION = "rust";

  @Override
  public Optional<RustToolchain> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    CxxPlatformsProvider cxxPlatformsProviderFactory =
        toolchainProvider.getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
    FlavorDomain<CxxPlatform> cxxPlatforms = cxxPlatformsProviderFactory.getCxxPlatforms();
    CxxPlatform defaultCxxPlatform = cxxPlatformsProviderFactory.getDefaultCxxPlatform();

    RustPlatformFactory platformFactory =
        RustPlatformFactory.of(context.getBuckConfig(), context.getExecutableFinder());

    FlavorDomain<RustPlatform> rustPlatforms =
        FlavorDomain.from(
            "Rust Platforms",
            RichStream.from(cxxPlatforms.getValues())
                // TODO: Allow overlaying flavor-specific section configuration.
                .map(cxxPlatform -> platformFactory.getPlatform(SECTION, cxxPlatform))
                .toImmutableList());
    RustPlatform defaultRustPlatform = rustPlatforms.getValue(defaultCxxPlatform.getFlavor());

    return Optional.of(RustToolchain.of(defaultRustPlatform, rustPlatforms));
  }
}
