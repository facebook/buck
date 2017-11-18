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
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractSdkEnvironment {
  // iOS
  public abstract Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> getAppleSdkPaths();

  public abstract Optional<ImmutableMap<String, AppleToolchain>> getAppleToolchains();
  // Android
  public abstract Optional<Path> getAndroidSdkPath();

  public abstract Optional<Path> getAndroidNdkPath();

  public abstract Optional<String> getNdkVersion();

  public static SdkEnvironment create(ToolchainProvider toolchainProvider) {
    Optional<AppleToolchainProvider> appleToolchainProvider =
        toolchainProvider.getByNameIfPresent(
            AppleToolchainProvider.DEFAULT_NAME, AppleToolchainProvider.class);
    Optional<ImmutableMap<String, AppleToolchain>> appleToolchains =
        appleToolchainProvider.map(AppleToolchainProvider::getAppleToolchains);

    Optional<AppleSdkLocation> appleSdkLocation =
        toolchainProvider.getByNameIfPresent(AppleSdkLocation.DEFAULT_NAME, AppleSdkLocation.class);
    Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> appleSdkPaths =
        appleSdkLocation.map(AppleSdkLocation::getAppleSdkPaths);

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
