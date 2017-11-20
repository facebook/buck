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

package com.facebook.buck.toolchain.impl;

import com.facebook.buck.android.AndroidLegacyToolchain;
import com.facebook.buck.android.DefaultAndroidLegacyToolchainFactory;
import com.facebook.buck.android.toolchain.AndroidToolchain;
import com.facebook.buck.android.toolchain.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.impl.DefaultAndroidToolchainFactory;
import com.facebook.buck.android.toolchain.impl.NdkCxxPlatformsProviderFactory;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.apple.toolchain.impl.AppleCxxPlatformsProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleDeveloperDirectoryProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleSdkLocationFactory;
import com.facebook.buck.apple.toolchain.impl.AppleToolchainProviderFactory;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.toolchain.BaseToolchainProvider;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class DefaultToolchainProvider extends BaseToolchainProvider {

  enum ToolchainDescriptor {
    ANDROID(AndroidToolchain.DEFAULT_NAME, DefaultAndroidToolchainFactory.class),
    ANDROID_LEGACY(AndroidLegacyToolchain.DEFAULT_NAME, DefaultAndroidLegacyToolchainFactory.class),
    NDK_CXX_PLATFORMS_PROVIDER(
        NdkCxxPlatformsProvider.DEFAULT_NAME, NdkCxxPlatformsProviderFactory.class),
    APPLE_DEVELOPER_DIRECTORY_PROVIDER(
        AppleDeveloperDirectoryProvider.DEFAULT_NAME, AppleDeveloperDirectoryProviderFactory.class),
    APPLE_TOOLCHAIN_PROVIDER(
        AppleToolchainProvider.DEFAULT_NAME, AppleToolchainProviderFactory.class),
    APPLE_SDK_LOCATION(AppleSdkLocation.DEFAULT_NAME, AppleSdkLocationFactory.class),
    APPLE_CXX_PLATFORMS_PROVIDER(
        AppleCxxPlatformsProvider.DEFAULT_NAME, AppleCxxPlatformsProviderFactory.class);

    @VisibleForTesting final String name;
    private final Class<? extends ToolchainFactory<?>> toolchainFactoryClass;

    ToolchainDescriptor(String name, Class<? extends ToolchainFactory<?>> toolchainFactoryClass) {
      this.name = name;
      this.toolchainFactoryClass = toolchainFactoryClass;
    }
  }

  private final ToolchainCreationContext toolchainCreationContext;
  private final ImmutableMap<String, Class<? extends ToolchainFactory<?>>> toolchainFactories;

  private final Map<String, Optional<? extends Toolchain>> toolchains = new HashMap<>();

  public DefaultToolchainProvider(
      ImmutableMap<String, String> environment,
      BuckConfig buckConfig,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor) {
    toolchainCreationContext =
        ToolchainCreationContext.builder()
            .setBuckConfig(buckConfig)
            .setFilesystem(projectFilesystem)
            .setEnvironment(environment)
            .setProcessExecutor(processExecutor)
            .build();

    ImmutableMap.Builder<String, Class<? extends ToolchainFactory<?>>> toolchainFactoriesBuilder =
        ImmutableMap.builder();
    for (ToolchainDescriptor toolchainDescriptor : ToolchainDescriptor.values()) {
      toolchainFactoriesBuilder.put(
          toolchainDescriptor.name, toolchainDescriptor.toolchainFactoryClass);
    }
    toolchainFactories = toolchainFactoriesBuilder.build();
  }

  @Override
  public synchronized Toolchain getByName(String toolchainName) {
    Optional<? extends Toolchain> toolchain = getOrCreate(toolchainName);
    if (toolchain.isPresent()) {
      return toolchain.get();
    } else {
      throw new HumanReadableException("Unknown toolchain: " + toolchainName);
    }
  }

  @Override
  public boolean isToolchainPresent(String toolchainName) {
    return toolchainFactories.containsKey(toolchainName) && getOrCreate(toolchainName).isPresent();
  }

  private Optional<? extends Toolchain> getOrCreate(String toolchainName) {
    Optional<? extends Toolchain> toolchain;
    if (!toolchains.containsKey(toolchainName)) {
      if (!toolchainFactories.containsKey(toolchainName)) {
        throw new IllegalStateException("Unknown toolchain: " + toolchainName);
      }
      Class<? extends ToolchainFactory<?>> toolchainFactoryClass =
          toolchainFactories.get(toolchainName);
      toolchain = createToolchain(toolchainFactoryClass);
      toolchains.put(toolchainName, toolchain);
    } else {
      toolchain = toolchains.get(toolchainName);
    }
    return toolchain;
  }

  private Optional<? extends Toolchain> createToolchain(
      Class<? extends ToolchainFactory<?>> toolchainFactoryClass) {
    ToolchainFactory<?> toolchainFactory;
    try {
      toolchainFactory = toolchainFactoryClass.newInstance();
    } catch (IllegalAccessException | InstantiationException e) {
      throw new RuntimeException(e);
    }
    return toolchainFactory.createToolchain(this, toolchainCreationContext);
  }
}
