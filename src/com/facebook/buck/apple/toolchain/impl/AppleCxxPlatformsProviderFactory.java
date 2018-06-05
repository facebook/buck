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

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainInstantiationException;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class AppleCxxPlatformsProviderFactory
    implements ToolchainFactory<AppleCxxPlatformsProvider> {

  @Override
  public Optional<AppleCxxPlatformsProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    Optional<AppleSdkLocation> appleSdkLocation =
        toolchainProvider.getByNameIfPresent(AppleSdkLocation.DEFAULT_NAME, AppleSdkLocation.class);
    Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> appleSdkPaths =
        appleSdkLocation.map(AppleSdkLocation::getAppleSdkPaths);

    Optional<AppleToolchainProvider> appleToolchainProvider =
        toolchainProvider.getByNameIfPresent(
            AppleToolchainProvider.DEFAULT_NAME, AppleToolchainProvider.class);
    Optional<ImmutableMap<String, AppleToolchain>> appleToolchains =
        appleToolchainProvider.map(AppleToolchainProvider::getAppleToolchains);

    try {
      return Optional.of(
          AppleCxxPlatformsProvider.of(
              create(
                  context.getBuckConfig(),
                  context.getFilesystem(),
                  appleSdkPaths,
                  appleToolchains)));
    } catch (HumanReadableException e) {
      throw ToolchainInstantiationException.wrap(e);
    }
  }

  private static FlavorDomain<AppleCxxPlatform> create(
      BuckConfig config,
      ProjectFilesystem filesystem,
      Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> appleSdkPaths,
      Optional<ImmutableMap<String, AppleToolchain>> appleToolchains) {
    ImmutableList<AppleCxxPlatform> appleCxxPlatforms =
        AppleCxxPlatforms.buildAppleCxxPlatforms(
            appleSdkPaths, appleToolchains, filesystem, config);
    checkApplePlatforms(appleCxxPlatforms);
    return FlavorDomain.from("Apple C++ Platform", appleCxxPlatforms);
  }

  private static void checkApplePlatforms(ImmutableList<AppleCxxPlatform> appleCxxPlatforms) {
    Map<Flavor, AppleCxxPlatform> platformsMap = new HashMap<>();
    for (AppleCxxPlatform platform : appleCxxPlatforms) {
      Flavor flavor = platform.getFlavor();
      if (platformsMap.containsKey(flavor)) {
        AppleCxxPlatform otherPlatform = platformsMap.get(flavor);
        throw new HumanReadableException(
            "There are two conflicting SDKs providing the same platform \"%s\":\n"
                + "- %s\n"
                + "- %s\n\n"
                + "Please try to remove one of them.",
            flavor.getName(),
            platform.getAppleSdkPaths().getSdkPath(),
            otherPlatform.getAppleSdkPaths().getSdkPath());
      }
      platformsMap.put(flavor, platform);
    }
  }
}
