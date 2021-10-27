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

package com.facebook.buck.apple.toolchain.impl;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.apple.toolchain.UnresolvedAppleCxxPlatform;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainInstantiationException;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class AppleCxxPlatformsProviderFactory
    implements ToolchainFactory<AppleCxxPlatformsProvider> {

  @Override
  public Optional<AppleCxxPlatformsProvider> createToolchain(
      ToolchainProvider toolchainProvider,
      ToolchainCreationContext context,
      TargetConfiguration toolchainTargetConfiguration) {
    AppleConfig appleConfig = context.getBuckConfig().getView(AppleConfig.class);
    Optional<AppleSdkLocation> appleSdkLocation =
        toolchainProvider.getByNameIfPresent(
            AppleSdkLocation.DEFAULT_NAME, toolchainTargetConfiguration, AppleSdkLocation.class);
    Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> appleSdkPaths =
        appleSdkLocation.map(AppleSdkLocation::getAppleSdkPaths);
    Optional<AppleToolchainProvider> appleToolchainProvider =
        toolchainProvider.getByNameIfPresent(
            AppleToolchainProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            AppleToolchainProvider.class);
    Optional<ImmutableMap<String, AppleToolchain>> appleToolchains =
        appleToolchainProvider.map(AppleToolchainProvider::getAppleToolchains);
    ImmutableList<AppleCxxPlatform> appleCxxPlatforms =
        buildStaticAppleCxxPlatforms(
            context.getBuckConfig(), context.getFilesystem(), appleSdkPaths, appleToolchains);
    Optional<BuildTarget> toolchainSetTarget =
        appleConfig.getAppleToolchainSetTarget(toolchainTargetConfiguration);
    if (toolchainSetTarget.isPresent()) {
      return Optional.of(
          AppleCxxPlatformsProvider.of(
              createDynamicPlatforms(toolchainSetTarget.get(), appleCxxPlatforms)));
    } else {
      return Optional.of(AppleCxxPlatformsProvider.of(createStaticPlatforms(appleCxxPlatforms)));
    }
  }

  private static ImmutableList<AppleCxxPlatform> buildStaticAppleCxxPlatforms(
      BuckConfig config,
      ProjectFilesystem filesystem,
      Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> appleSdkPaths,
      Optional<ImmutableMap<String, AppleToolchain>> appleToolchains) {
    ImmutableList<AppleCxxPlatform> appleCxxPlatforms =
        AppleCxxPlatforms.buildAppleCxxPlatforms(
            appleSdkPaths, appleToolchains, filesystem, config);
    try {
      checkApplePlatforms(appleCxxPlatforms);
    } catch (HumanReadableException e) {
      throw ToolchainInstantiationException.wrap(e);
    }
    return appleCxxPlatforms;
  }

  private static FlavorDomain<UnresolvedAppleCxxPlatform> createStaticPlatforms(
      ImmutableList<AppleCxxPlatform> appleCxxPlatforms) {
    ImmutableList<UnresolvedAppleCxxPlatform> unresolvedAppleCxxPlatforms =
        appleCxxPlatforms.stream()
            .map(platform -> StaticUnresolvedAppleCxxPlatform.of(platform, platform.getFlavor()))
            .collect(ImmutableList.toImmutableList());
    return FlavorDomain.from("Apple C++ Platform", unresolvedAppleCxxPlatforms);
  }

  private static void checkApplePlatforms(ImmutableList<AppleCxxPlatform> appleCxxPlatforms)
      throws HumanReadableException {
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

  private static FlavorDomain<UnresolvedAppleCxxPlatform> createDynamicPlatforms(
      BuildTarget toolchainSetTarget, Iterable<AppleCxxPlatform> fallbackPlatforms) {

    ImmutableMap.Builder<Flavor, AppleCxxPlatform> fallbackPlatformBuilder = ImmutableMap.builder();
    for (AppleCxxPlatform platform : fallbackPlatforms) {
      fallbackPlatformBuilder.put(platform.getFlavor(), platform);
    }
    ImmutableMap<Flavor, AppleCxxPlatform> fallbackPlatformMap = fallbackPlatformBuilder.build();

    ImmutableList<UnresolvedAppleCxxPlatform> unresolvedAppleCxxPlatforms =
        ApplePlatform.ALL_PLATFORM_FLAVORS.stream()
            .map(
                flavor ->
                    new ProviderBackedUnresolvedAppleCxxPlatform(
                        toolchainSetTarget,
                        flavor,
                        fallbackPlatformMap.containsKey(flavor)
                            ? Optional.of(fallbackPlatformMap.get(flavor))
                            : Optional.empty()))
            .collect(ImmutableList.toImmutableList());
    return FlavorDomain.from("Apple C++ Platform", unresolvedAppleCxxPlatforms);
  }
}
