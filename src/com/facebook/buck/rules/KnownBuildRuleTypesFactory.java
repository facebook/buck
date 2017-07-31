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

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleCxxPlatforms;
import com.facebook.buck.apple.AppleSdk;
import com.facebook.buck.apple.AppleSdkDiscovery;
import com.facebook.buck.apple.AppleSdkPaths;
import com.facebook.buck.apple.AppleToolchain;
import com.facebook.buck.apple.AppleToolchainDiscovery;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Contain items used to construct a {@link KnownBuildRuleTypes} that are shared between all {@link
 * Cell} instances.
 */
public class KnownBuildRuleTypesFactory {

  private static final Logger LOG = Logger.get(KnownBuildRuleTypesFactory.class);

  private final ProcessExecutor executor;
  private final AndroidDirectoryResolver directoryResolver;

  public KnownBuildRuleTypesFactory(
      ProcessExecutor executor, AndroidDirectoryResolver directoryResolver) {
    this.executor = executor;
    this.directoryResolver = directoryResolver;
  }

  public KnownBuildRuleTypes create(
      BuckConfig config, ProjectFilesystem filesystem, SdkEnvironment sdkEnvironment)
      throws IOException, InterruptedException {
    return KnownBuildRuleTypes.createInstance(
        config, filesystem, executor, directoryResolver, sdkEnvironment);
  }

  public SdkEnvironment createSdkEnvironment(BuckConfig config) {
    Optional<Path> appleDeveloperDir =
        AppleCxxPlatforms.getAppleDeveloperDirectory(config, this.executor);

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

    return SdkEnvironment.of(
        appleSdkPaths,
        appleToolchains,
        directoryResolver.getSdkOrAbsent(),
        directoryResolver.getNdkOrAbsent(),
        directoryResolver.getNdkVersion());
  }
}
