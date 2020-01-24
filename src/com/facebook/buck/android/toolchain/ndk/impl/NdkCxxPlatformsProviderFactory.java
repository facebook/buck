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

package com.facebook.buck.android.toolchain.ndk.impl;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.ndk.NdkTargetArchAbi;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.android.toolchain.ndk.UnresolvedNdkCxxPlatform;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainInstantiationException;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

public class NdkCxxPlatformsProviderFactory implements ToolchainFactory<NdkCxxPlatformsProvider> {

  @Override
  public Optional<NdkCxxPlatformsProvider> createToolchain(
      ToolchainProvider toolchainProvider,
      ToolchainCreationContext context,
      TargetConfiguration toolchainTargetConfiguration) {
    Optional<NdkCxxPlatformsProvider> dynamicNdkCxxPlatformsProvider =
        getDynamicNdkCxxPlatformsProvider(context.getBuckConfig(), toolchainTargetConfiguration);
    if (dynamicNdkCxxPlatformsProvider.isPresent()) {
      return dynamicNdkCxxPlatformsProvider;
    }
    ImmutableMap<TargetCpuType, UnresolvedNdkCxxPlatform> ndkCxxPlatforms =
        getNdkCxxPlatforms(
            context.getBuckConfig(),
            context.getFilesystem(),
            toolchainTargetConfiguration,
            toolchainProvider);

    return Optional.of(NdkCxxPlatformsProvider.of(ndkCxxPlatforms));
  }

  private static ImmutableMap<TargetCpuType, UnresolvedNdkCxxPlatform> getNdkCxxPlatforms(
      BuckConfig config,
      ProjectFilesystem filesystem,
      TargetConfiguration targetConfiguration,
      ToolchainProvider toolchainProvider) {

    Platform platform = Platform.detect();
    AndroidBuckConfig androidConfig = new AndroidBuckConfig(config, platform);
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);

    String ndkVersion;
    if (androidConfig.getNdkVersion().isPresent()) {
      ndkVersion = androidConfig.getNdkVersion().get();
    } else {
      AndroidNdk androidNdk =
          toolchainProvider.getByName(
              AndroidNdk.DEFAULT_NAME, targetConfiguration, AndroidNdk.class);
      ndkVersion = androidNdk.getNdkVersion();
    }

    try {
      return NdkCxxPlatforms.getPlatforms(
          cxxBuckConfig,
          androidConfig,
          filesystem,
          targetConfiguration,
          platform,
          toolchainProvider,
          ndkVersion);
    } catch (AssertionError e) {
      throw new ToolchainInstantiationException(e, e.getMessage());
    }
  }

  private static Optional<NdkCxxPlatformsProvider> getDynamicNdkCxxPlatformsProvider(
      BuckConfig config, TargetConfiguration toolchainTargetConfiguration) {
    AndroidBuckConfig androidConfig = new AndroidBuckConfig(config, Platform.detect());
    Optional<ImmutableSet<NdkTargetArchAbi>> cpuAbis = androidConfig.getNdkCpuAbis();
    if (!cpuAbis.isPresent()) {
      return Optional.empty();
    }
    ImmutableMap.Builder<TargetCpuType, UnresolvedNdkCxxPlatform> ndkCxxPlatformBuilder =
        ImmutableMap.builder();
    for (NdkTargetArchAbi cpuAbi : cpuAbis.get()) {
      TargetCpuType cpuType = cpuAbi.getTargetCpuType();
      Flavor flavor = InternalFlavor.of("android-" + cpuType.toString().toLowerCase());
      Optional<UnresolvedNdkCxxPlatform> ndkCxxPlatform =
          androidConfig
              .getNdkCxxToolchainTargetForAbi(cpuAbi, toolchainTargetConfiguration)
              .map(target -> new ProviderBackedUnresolvedNdkCxxPlatform(target, flavor));
      ndkCxxPlatform.ifPresent(
          unresolvedNdkCxxPlatform -> ndkCxxPlatformBuilder.put(cpuType, unresolvedNdkCxxPlatform));
    }
    ImmutableMap<TargetCpuType, UnresolvedNdkCxxPlatform> ndkCxxPlatforms =
        ndkCxxPlatformBuilder.build();
    if (ndkCxxPlatforms.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(NdkCxxPlatformsProvider.of(ndkCxxPlatforms));
  }
}
