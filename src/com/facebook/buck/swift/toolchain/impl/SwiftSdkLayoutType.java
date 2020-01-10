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

package com.facebook.buck.swift.toolchain.impl;

import com.facebook.buck.apple.platform_type.ApplePlatformType;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.VersionStringComparator;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

/** Determine Swift SDK layout so the correct lib directory flags are specified during linking */
public enum SwiftSdkLayoutType {
  XCODE_11, // Targetting iOS 13, Mac 10.15, with Swift libs in *.sdk/usr/lib/swift
  XCODE_11_HYBRID, // Targetting iOS 13, Mac 10.15, with Swift libs in .xctoolchain
  PRE_XCODE_11; // Targetting iOS <13, Mac <10.15 with Swift libs in .xctoolchain

  private static final String IOS_VERSION_AT_XCODE_11 = "13.0";
  private static final String TV_VERSION_AT_XCODE_11 = "13.0";
  private static final String MAC_VERSION_AT_XCODE_11 = "10.15";
  private static final String WATCH_VERSION_AT_XCODE_11 = "6.0";

  private static final Logger LOG = Logger.get(SwiftSdkLayoutType.class);

  private static SwiftSdkLayoutType of(AppleSdk sdk, AppleSdkPaths sdkPaths) {
    if (compareWithXcode11(sdk) < 0) {
      return PRE_XCODE_11;
    } else if (compareWithXcode11(sdk) == 0) {
      if (Files.isDirectory(getSwiftRuntimePathFromSdk(sdkPaths))) {
        return XCODE_11;
      } else {
        return XCODE_11_HYBRID;
      }
    } else {
      return XCODE_11;
    }
  }

  /** Given the SDK layout and version, return the correct list of lib directory paths */
  public static ImmutableList<Path> getLinkPaths(
      AppleSdk sdk, AppleSdkPaths sdkPaths, String swiftName) {
    SwiftSdkLayoutType layoutType = of(sdk, sdkPaths);
    ArrayList<Path> paths = new ArrayList<Path>();
    LOG.debug("Swift layout type is %s", layoutType);
    switch (layoutType) {
      case XCODE_11:
        paths.add(getSwiftRuntimePathFromSdk(sdkPaths));
        for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
          paths.add(
              Paths.get(getSwiftRuntimePathFromToolchain(toolchainPath).toString(), swiftName));
        }
        break;
      case XCODE_11_HYBRID:
      case PRE_XCODE_11:
        for (Path toolchainPath : sdkPaths.getToolchainPaths()) {
          paths.add(
              Paths.get(getSwiftRuntimePathFromToolchain(toolchainPath).toString(), swiftName));
        }
        break;
    }
    return ImmutableList.copyOf(paths);
  }

  private static Path getSwiftRuntimePathFromSdk(AppleSdkPaths sdkPaths) {
    return Paths.get(sdkPaths.getSdkPath().toString(), "usr/lib/swift");
  }

  private static Path getSwiftRuntimePathFromToolchain(Path toolchainPath) {
    return Paths.get(toolchainPath.toString(), "usr/lib/swift");
  }

  private static int compareWithXcode11(AppleSdk sdk) {
    ApplePlatformType platformType = ApplePlatformType.of(sdk.getApplePlatform().getName());
    VersionStringComparator comparator = new VersionStringComparator();
    String sdkVersion = sdk.getVersion();
    switch (platformType) {
      case WATCH_DEVICE:
      case WATCH_SIMULATOR:
        return comparator.compare(sdkVersion, WATCH_VERSION_AT_XCODE_11);
      case MAC:
        return comparator.compare(sdkVersion, MAC_VERSION_AT_XCODE_11);
      case IOS_DEVICE:
      case IOS_SIMULATOR:
        return comparator.compare(sdkVersion, IOS_VERSION_AT_XCODE_11);
      case TV_DEVICE:
      case TV_SIMULATOR:
        return comparator.compare(sdkVersion, TV_VERSION_AT_XCODE_11);
      case UNKNOWN:
      default:
        return -1;
    }
  }
}
