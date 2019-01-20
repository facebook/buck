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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.apple.toolchain.AppleToolchain;
import com.facebook.buck.apple.toolchain.impl.AppleCxxPlatforms;
import com.facebook.buck.apple.toolchain.impl.AppleSdkDiscovery;
import com.facebook.buck.apple.toolchain.impl.AppleToolchainDiscovery;
import com.facebook.buck.apple.toolchain.impl.XcodeToolFinder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class AppleNativeIntegrationTestUtils {

  private AppleNativeIntegrationTestUtils() {}

  private static ImmutableMap<AppleSdk, AppleSdkPaths> discoverSystemSdkPaths(
      BuckConfig buckConfig) {
    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());
    Optional<Path> appleDeveloperDirectory =
        appleConfig.getAppleDeveloperDirectorySupplier(executor).get();
    try {
      ImmutableMap<String, AppleToolchain> toolchains =
          AppleToolchainDiscovery.discoverAppleToolchains(
              appleDeveloperDirectory, appleConfig.getExtraToolchainPaths());
      return AppleSdkDiscovery.discoverAppleSdkPaths(
          appleDeveloperDirectory, appleConfig.getExtraPlatformPaths(), toolchains, appleConfig);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Optional<AppleSdk> anySdkForPlatform(
      ApplePlatform platform, ImmutableMap<AppleSdk, AppleSdkPaths> sdkPaths) {
    return sdkPaths
        .keySet()
        .stream()
        .filter(sdk -> sdk.getApplePlatform().equals(platform))
        .findFirst();
  }

  public static boolean isApplePlatformAvailable(ApplePlatform platform) {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    return anySdkForPlatform(platform, discoverSystemSdkPaths(buckConfig)).isPresent();
  }

  public static boolean isSwiftAvailable(ApplePlatform platform) {
    BuckConfig buckConfig = FakeBuckConfig.builder().build();
    ImmutableMap<AppleSdk, AppleSdkPaths> sdkPaths = discoverSystemSdkPaths(buckConfig);
    Optional<AppleSdk> anySdkOptional = anySdkForPlatform(platform, sdkPaths);
    if (!anySdkOptional.isPresent()) {
      return false;
    }
    AppleSdk anySdk = anySdkOptional.get();
    AppleCxxPlatform appleCxxPlatform =
        AppleCxxPlatforms.buildWithXcodeToolFinder(
            new FakeProjectFilesystem(),
            anySdk,
            "fakeversion",
            "fakearch",
            sdkPaths.get(anySdk),
            buckConfig,
            new XcodeToolFinder(buckConfig.getView(AppleConfig.class)),
            FakeAppleRuleDescriptions.FAKE_XCODE_BUILD_VERSION_CACHE);
    return appleCxxPlatform.getSwiftPlatform().isPresent();
  }
}
