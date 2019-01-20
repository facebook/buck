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

package com.facebook.buck.apple.toolchain.impl;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class AppleToolchainProviderFactory implements ToolchainFactory<AppleToolchainProvider> {
  private static final Logger LOG = Logger.get(AppleToolchainProviderFactory.class);

  @Override
  public Optional<AppleToolchainProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    Optional<Path> appleDeveloperDir =
        toolchainProvider
            .getByNameIfPresent(
                AppleDeveloperDirectoryProvider.DEFAULT_NAME, AppleDeveloperDirectoryProvider.class)
            .map(AppleDeveloperDirectoryProvider::getAppleDeveloperDirectory);

    AppleConfig appleConfig = context.getBuckConfig().getView(AppleConfig.class);
    Optional<AppleToolchainProvider> appleToolchainProvider;
    try {
      ImmutableMap<String, AppleToolchain> appleToolchains =
          AppleToolchainDiscovery.discoverAppleToolchains(
              appleDeveloperDir, appleConfig.getExtraToolchainPaths());
      appleToolchainProvider = Optional.of(AppleToolchainProvider.of(appleToolchains));
    } catch (IOException e) {
      LOG.error(
          e,
          "Couldn't find the Apple build toolchain.\nPlease check that the SDK is installed properly.");
      appleToolchainProvider = Optional.empty();
    }
    return appleToolchainProvider;
  }
}
