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

package com.facebook.buck.android.toolchain.impl;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.NdkCxxPlatforms;
import com.facebook.buck.android.toolchain.AndroidNdk;
import com.facebook.buck.android.toolchain.AndroidToolchain;
import com.facebook.buck.android.toolchain.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.TargetCpuType;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

public class NdkCxxPlatformsProviderFactory {
  public static NdkCxxPlatformsProvider create(
      BuckConfig config, ProjectFilesystem filesystem, ToolchainProvider toolchainProvider) {

    Platform platform = Platform.detect();
    AndroidBuckConfig androidConfig = new AndroidBuckConfig(config, platform);
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);

    Optional<String> ndkVersion = androidConfig.getNdkVersion();
    if (!ndkVersion.isPresent()
        && toolchainProvider.isToolchainPresent(AndroidToolchain.DEFAULT_NAME)) {
      AndroidToolchain androidToolchain =
          toolchainProvider.getByName(AndroidToolchain.DEFAULT_NAME, AndroidToolchain.class);
      ndkVersion = androidToolchain.getAndroidNdk().map(AndroidNdk::getNdkVersion);
    }

    ImmutableMap<TargetCpuType, NdkCxxPlatform> ndkCxxPlatforms =
        NdkCxxPlatforms.getPlatforms(
            cxxBuckConfig, androidConfig, filesystem, platform, toolchainProvider, ndkVersion);

    return new NdkCxxPlatformsProvider(ndkCxxPlatforms);
  }
}
