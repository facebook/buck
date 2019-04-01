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

package com.facebook.buck.android.toolchain.ndk.impl;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.android.toolchain.ndk.UnresolvedNdkCxxPlatform;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainInstantiationException;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

public class NdkCxxPlatformsProviderFactory implements ToolchainFactory<NdkCxxPlatformsProvider> {

  @Override
  public Optional<NdkCxxPlatformsProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    ImmutableMap<TargetCpuType, UnresolvedNdkCxxPlatform> ndkCxxPlatforms =
        getNdkCxxPlatforms(
            context.getBuckConfig(),
            context.getFilesystem(),
            context.getTargetConfiguration().get(),
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
          toolchainProvider.getByName(AndroidNdk.DEFAULT_NAME, AndroidNdk.class);
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
}
