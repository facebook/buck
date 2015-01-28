/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.util.environment.Platform;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class holding pre-made fake Apple rule descriptions for use in tests.
 */
public class FakeAppleRuleDescriptions {
  // Utility class, do not instantiate.
  private FakeAppleRuleDescriptions() { }

  private static final ImmutableMap<Path, Boolean> DEFAULT_TOOL_EXECUTABLE_CHECKER =
      ImmutableMap.<Path, Boolean>builder()
        .put(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"), true)
        .put(Paths.get("Toolchains/XcodeDefault.xctoolchain/usr/bin/clang++"), true)
        .put(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/libtool"), true)
        .put(Paths.get("Platforms/iPhoneOS.platform/Developer/usr/bin/ar"), true)
        .build();

  private static final AppleSdkPaths DEFAULT_IPHONEOS_SDK_PATHS =
      ImmutableAppleSdkPaths.builder()
          .setDeveloperPath(Paths.get("."))
          .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
          .setPlatformDeveloperPath(Paths.get("Platforms/iPhoneOS.platform/Developer"))
          .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"))
          .build();

  private static final CxxPlatform DEFAULT_IPHONEOS_PLATFORM =
      AppleCxxPlatforms.buildWithExecutableChecker(
          ApplePlatform.IPHONEOS,
          "iphoneos",
          "8.0",
          "i386",
          DEFAULT_IPHONEOS_SDK_PATHS,
          Functions.forMap(DEFAULT_TOOL_EXECUTABLE_CHECKER, false));

  private static final BuckConfig DEFAULT_BUCK_CONFIG = new FakeBuckConfig();

  private static final CxxPlatform DEFAULT_PLATFORM = DefaultCxxPlatforms.build(
      Platform.MACOS,
      DEFAULT_BUCK_CONFIG);

  private static final FlavorDomain<CxxPlatform> DEFAULT_IPHONEOS_FLAVOR_DOMAIN =
      new FlavorDomain<>(
          "Fake iPhone C/C++ Platform",
          ImmutableMap.of(
              DEFAULT_PLATFORM.getFlavor(),
              DEFAULT_PLATFORM,
              DEFAULT_IPHONEOS_PLATFORM.getFlavor(),
              DEFAULT_IPHONEOS_PLATFORM));

  private static final ImmutableMap<CxxPlatform, AppleSdkPaths>
    DEFAULT_CXX_PLATFORM_TO_APPLE_SDK_PATHS =
      ImmutableMap.of(DEFAULT_PLATFORM, DEFAULT_IPHONEOS_SDK_PATHS);

  /**
   * A fake apple_library description with an iOS platform for use in tests.
   */
  public static final AppleLibraryDescription LIBRARY_DESCRIPTION =
    new AppleLibraryDescription(
        new AppleConfig(DEFAULT_BUCK_CONFIG),
        new CxxLibraryDescription(
            new CxxBuckConfig(DEFAULT_BUCK_CONFIG),
            DEFAULT_IPHONEOS_FLAVOR_DOMAIN),
        DEFAULT_IPHONEOS_FLAVOR_DOMAIN,
        DEFAULT_CXX_PLATFORM_TO_APPLE_SDK_PATHS);

  /**
   * A fake apple_binary description with an iOS platform for use in tests.
   */
  public static final AppleBinaryDescription BINARY_DESCRIPTION =
    new AppleBinaryDescription(
        new AppleConfig(DEFAULT_BUCK_CONFIG),
        new CxxBinaryDescription(
            new CxxBuckConfig(DEFAULT_BUCK_CONFIG),
            DEFAULT_IPHONEOS_PLATFORM,
            DEFAULT_IPHONEOS_FLAVOR_DOMAIN),
        DEFAULT_IPHONEOS_FLAVOR_DOMAIN,
        DEFAULT_CXX_PLATFORM_TO_APPLE_SDK_PATHS);
}
