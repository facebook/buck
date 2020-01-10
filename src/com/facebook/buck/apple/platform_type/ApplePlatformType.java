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

package com.facebook.buck.apple.platform_type;

public enum ApplePlatformType {
  MAC,
  IOS_DEVICE,
  IOS_SIMULATOR,
  WATCH_DEVICE,
  WATCH_SIMULATOR,
  TV_DEVICE,
  TV_SIMULATOR,
  UNKNOWN;

  public boolean isWatch() {
    switch (this) {
      case WATCH_DEVICE:
      case WATCH_SIMULATOR:
        return true;
      case MAC:
      case IOS_DEVICE:
      case IOS_SIMULATOR:
      case TV_DEVICE:
      case TV_SIMULATOR:
      case UNKNOWN:
        break;
    }

    return false;
  }

  public static ApplePlatformType of(String platformName) {
    if (platformName.contains("osx")) {
      return ApplePlatformType.MAC;
    }

    if (platformName.contains("iphoneos")) {
      return ApplePlatformType.IOS_DEVICE;
    }

    if (platformName.contains("iphonesimulator")) {
      return ApplePlatformType.IOS_SIMULATOR;
    }

    if (platformName.contains("watchos")) {
      return ApplePlatformType.WATCH_DEVICE;
    }

    if (platformName.contains("watchsimulator")) {
      return ApplePlatformType.WATCH_SIMULATOR;
    }

    if (platformName.contains("appletvos")) {
      return ApplePlatformType.TV_DEVICE;
    }

    if (platformName.contains("appletvsimulator")) {
      return ApplePlatformType.TV_SIMULATOR;
    }

    return ApplePlatformType.UNKNOWN;
  }
}
