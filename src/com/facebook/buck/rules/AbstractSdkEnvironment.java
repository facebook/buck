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
import com.facebook.buck.apple.AppleSdkDiscovery;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.toolchain.ToolchainProvider;
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

  public static SdkEnvironment create(BuckConfig config, ToolchainProvider toolchainProvider) {
    Optional<ImmutableMap<String, AppleToolchain>> appleToolchains = Optional.empty();
    Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> appleSdkPaths = Optional.empty();

    if (toolchainProvider.isToolchainPresent(AppleToolchainProvider.DEFAULT_NAME)) {
      Optional<Path> appleDeveloperDir =
          toolchainProvider
              .getByNameIfPresent(
                  AppleDeveloperDirectoryProvider.DEFAULT_NAME,
                  AppleDeveloperDirectoryProvider.class)
              .map(AppleDeveloperDirectoryProvider::getAppleDeveloperDirectory);

      AppleConfig appleConfig = config.getView(AppleConfig.class);
      AppleToolchainProvider appleToolchainProvider =
          toolchainProvider.getByName(
              AppleToolchainProvider.DEFAULT_NAME, AppleToolchainProvider.class);
      try {
        appleSdkPaths =
            Optional.of(
                AppleSdkDiscovery.discoverAppleSdkPaths(
                    appleDeveloperDir,
                    appleConfig.getExtraPlatformPaths(),
                    appleToolchainProvider.getAppleToolchains(),
                    appleConfig));
        appleToolchains = Optional.of(appleToolchainProvider.getAppleToolchains());
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
