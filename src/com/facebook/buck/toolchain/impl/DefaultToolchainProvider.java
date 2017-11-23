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
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.impl.AndroidSdkLocationFactory;
import com.facebook.buck.android.toolchain.impl.NdkCxxPlatformsProviderFactory;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkFactory;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.apple.toolchain.impl.AppleCxxPlatformsProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleDeveloperDirectoryProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleSdkLocationFactory;
import com.facebook.buck.apple.toolchain.impl.AppleToolchainProviderFactory;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProviderFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.swift.toolchain.impl.SwiftPlatformsProviderFactory;
import com.facebook.buck.toolchain.BaseToolchainProvider;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainWithCapability;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class DefaultToolchainProvider extends BaseToolchainProvider {

  enum ToolchainDescriptor {
    ANDROID_LEGACY(
        AndroidLegacyToolchain.DEFAULT_NAME,
        AndroidLegacyToolchain.class,
        DefaultAndroidLegacyToolchainFactory.class),
    ANDROID_SDK_LOCATION(
        AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class, AndroidSdkLocationFactory.class),
    ANDROID_NDK(AndroidNdk.DEFAULT_NAME, AndroidNdk.class, AndroidNdkFactory.class),
    NDK_CXX_PLATFORMS_PROVIDER(
        NdkCxxPlatformsProvider.DEFAULT_NAME,
        NdkCxxPlatformsProvider.class,
        NdkCxxPlatformsProviderFactory.class),
    APPLE_DEVELOPER_DIRECTORY_PROVIDER(
        AppleDeveloperDirectoryProvider.DEFAULT_NAME,
        AppleDeveloperDirectoryProvider.class,
        AppleDeveloperDirectoryProviderFactory.class),
    APPLE_TOOLCHAIN_PROVIDER(
        AppleToolchainProvider.DEFAULT_NAME,
        AppleToolchainProvider.class,
        AppleToolchainProviderFactory.class),
    APPLE_SDK_LOCATION(
        AppleSdkLocation.DEFAULT_NAME, AppleSdkLocation.class, AppleSdkLocationFactory.class),
    APPLE_CXX_PLATFORMS_PROVIDER(
        AppleCxxPlatformsProvider.DEFAULT_NAME,
        AppleCxxPlatformsProvider.class,
        AppleCxxPlatformsProviderFactory.class),
    SWIFT_PLATFORMS_PROVIDER(
        SwiftPlatformsProvider.DEFAULT_NAME,
        SwiftPlatformsProvider.class,
        SwiftPlatformsProviderFactory.class),
    CXX_PLATFORMS(
        CxxPlatformsProvider.DEFAULT_NAME,
        CxxPlatformsProvider.class,
        CxxPlatformsProviderFactory.class);

    @VisibleForTesting final String name;
    private final Class<? extends Toolchain> toolchainClass;
    private final Class<? extends ToolchainFactory<?>> toolchainFactoryClass;

    <T extends Toolchain> ToolchainDescriptor(
        String name,
        Class<T> toolchainClass,
        Class<? extends ToolchainFactory<T>> toolchainFactoryClass) {
      this.name = name;
      this.toolchainClass = toolchainClass;
      this.toolchainFactoryClass = toolchainFactoryClass;
    }
  }

  private final ToolchainCreationContext toolchainCreationContext;
  private final ImmutableMap<String, Class<? extends ToolchainFactory<?>>> toolchainFactories;

  private final LoadingCache<String, Optional<? extends Toolchain>> toolchains =
      CacheBuilder.newBuilder()
          .maximumSize(1024)
          .build(
              new CacheLoader<String, Optional<? extends Toolchain>>() {
                @Override
                public Optional<? extends Toolchain> load(String toolchainName) throws Exception {
                  if (!toolchainFactories.containsKey(toolchainName)) {
                    throw new IllegalStateException("Unknown toolchain: " + toolchainName);
                  }
                  return createToolchain(toolchainFactories.get(toolchainName));
                }
              });

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
  public Toolchain getByName(String toolchainName) {
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

  @Override
  public <T extends ToolchainWithCapability> Collection<String> getToolchainsWithCapability(
      Class<T> capability) {
    ImmutableList.Builder<String> toolchainsWithCapabilities = ImmutableList.builder();

    for (ToolchainDescriptor toolchainDescriptor : ToolchainDescriptor.values()) {
      if (capability.isAssignableFrom(toolchainDescriptor.toolchainClass)) {
        toolchainsWithCapabilities.add(toolchainDescriptor.name);
      }
    }

    return toolchainsWithCapabilities.build();
  }

  private Optional<? extends Toolchain> getOrCreate(String toolchainName) {
    try {
      return toolchains.get(toolchainName);
    } catch (ExecutionException | UncheckedExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), HumanReadableException.class);
      throw new HumanReadableException(e, "Cannot create a toolchain: " + toolchainName);
    }
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
