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
import com.facebook.buck.apple.toolchain.AbstractAppleToolchainProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainInstantiationException;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class AppleSdkLocationFactory implements ToolchainFactory<AppleSdkLocation> {

  @Override
  public Optional<AppleSdkLocation> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    if (!toolchainProvider.isToolchainPresent(AbstractAppleToolchainProvider.DEFAULT_NAME)) {
      return Optional.empty();
    }

    Optional<Path> appleDeveloperDir =
        toolchainProvider
            .getByNameIfPresent(
                AppleDeveloperDirectoryProvider.DEFAULT_NAME, AppleDeveloperDirectoryProvider.class)
            .map(AppleDeveloperDirectoryProvider::getAppleDeveloperDirectory);

    AppleConfig appleConfig = context.getBuckConfig().getView(AppleConfig.class);
    AbstractAppleToolchainProvider appleToolchainProvider =
        toolchainProvider.getByName(
            AbstractAppleToolchainProvider.DEFAULT_NAME, AbstractAppleToolchainProvider.class);
    try {
      ImmutableMap<AppleSdk, AppleSdkPaths> appleSdkPaths =
          AppleSdkDiscovery.discoverAppleSdkPaths(
              appleDeveloperDir,
              appleConfig.getExtraPlatformPaths(),
              appleToolchainProvider.getAppleToolchains(),
              appleConfig);
      return Optional.of(AppleSdkLocation.of(appleSdkPaths));
    } catch (IOException e) {
      throw new ToolchainInstantiationException(
          e, "Couldn't find the Apple SDK.\nPlease check that the SDK is installed properly.");
    }
  }
}
