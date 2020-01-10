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

package com.facebook.buck.features.rust;

import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.util.stream.RichStream;
import java.util.Optional;

/** Constructs {@link RustToolchain}s. */
public class RustToolchainFactory implements ToolchainFactory<RustToolchain> {

  @Override
  public Optional<RustToolchain> createToolchain(
      ToolchainProvider toolchainProvider,
      ToolchainCreationContext context,
      TargetConfiguration toolchainTargetConfiguration) {

    CxxPlatformsProvider cxxPlatformsProviderFactory =
        toolchainProvider.getByName(
            CxxPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            CxxPlatformsProvider.class);
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProviderFactory.getUnresolvedCxxPlatforms();
    UnresolvedCxxPlatform defaultCxxPlatform =
        cxxPlatformsProviderFactory.getDefaultUnresolvedCxxPlatform();

    RustPlatformFactory platformFactory =
        ImmutableRustPlatformFactory.of(context.getBuckConfig(), context.getExecutableFinder());

    FlavorDomain<UnresolvedRustPlatform> rustPlatforms =
        FlavorDomain.from(
            "Rust Platforms",
            RichStream.from(cxxPlatforms.getValues())
                // TODO: Allow overlaying flavor-specific section configuration.
                .map(
                    cxxPlatform ->
                        platformFactory.getPlatform(
                            cxxPlatform.getFlavor().getName(),
                            cxxPlatform,
                            context.getProcessExecutor()))
                .toImmutableList());
    UnresolvedRustPlatform defaultRustPlatform =
        rustPlatforms.getValue(defaultCxxPlatform.getFlavor());

    return Optional.of(RustToolchain.of(defaultRustPlatform, rustPlatforms));
  }
}
