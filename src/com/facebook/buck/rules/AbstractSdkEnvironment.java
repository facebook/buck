/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.android.toolchain.AndroidNdk;
import com.facebook.buck.android.toolchain.AndroidToolchain;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleCxxPlatforms;
import com.facebook.buck.apple.AppleSdk;
import com.facebook.buck.apple.AppleSdkDiscovery;
import com.facebook.buck.apple.AppleSdkPaths;
import com.facebook.buck.apple.AppleToolchain;
import com.facebook.buck.apple.AppleToolchainDiscovery;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractSdkEnvironment {
  private static final Logger LOG = Logger.get(AbstractSdkEnvironment.class);

  // iOS
  public abstract Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> getAppleSdkPaths();

  public abstract Optional<ImmutableMap<String, AppleToolchain>> getAppleToolchains();
  // Android
  public abstract Optional<Path> getAndroidSdkPath();

  public abstract Optional<Path> getAndroidNdkPath();

  public abstract Optional<String> getNdkVersion();

  public static SdkEnvironment create(
      BuckConfig config, ProcessExecutor executor, ToolchainProvider toolchainProvider) {
    Optional<Path> appleDeveloperDir =
        AppleCxxPlatforms.getAppleDeveloperDirectory(config, executor);

    AppleConfig appleConfig = config.getView(AppleConfig.class);
    Optional<ImmutableMap<String, AppleToolchain>> appleToolchains = Optional.empty();
    try {
      appleToolchains =
          Optional.of(
              AppleToolchainDiscovery.discoverAppleToolchains(
                  appleDeveloperDir, appleConfig.getExtraToolchainPaths()));
    } catch (IOException e) {
      LOG.error(
          e,
          "Couldn't find the Apple build toolchain.\nPlease check that the SDK is installed properly.");
    }

    Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> appleSdkPaths = Optional.empty();
    if (appleToolchains.isPresent()) {
      try {
        appleSdkPaths =
            Optional.of(
                AppleSdkDiscovery.discoverAppleSdkPaths(
                    appleDeveloperDir,
                    appleConfig.getExtraPlatformPaths(),
                    appleToolchains.get(),
                    appleConfig));
      } catch (IOException e) {
        LOG.error(
            e, "Couldn't find the Apple SDK.\nPlease check that the SDK is installed properly.");
      }
    }

    Optional<Path> androidSdkRoot;
    Optional<Path> androidNdkRoot;
    Optional<String> androidNdkVersion;
    if (toolchainProvider.isToolchainPresent(AndroidToolchain.DEFAULT_NAME)) {
      AndroidToolchain androidToolchain =
          toolchainProvider.getByName(AndroidToolchain.DEFAULT_NAME, AndroidToolchain.class);
      androidSdkRoot = Optional.of(androidToolchain.getAndroidSdk().getSdkRootPath());
      androidNdkRoot = androidToolchain.getAndroidNdk().map(AndroidNdk::getNdkRootPath);
      androidNdkVersion = androidToolchain.getAndroidNdk().map(AndroidNdk::getNdkVersion);
    } else {
      androidSdkRoot = Optional.empty();
      androidNdkRoot = Optional.empty();
      androidNdkVersion = Optional.empty();
    }

    return SdkEnvironment.of(
        appleSdkPaths, appleToolchains, androidSdkRoot, androidNdkRoot, androidNdkVersion);
  }
}
