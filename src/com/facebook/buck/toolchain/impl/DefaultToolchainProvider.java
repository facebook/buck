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
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.apple.toolchain.AppleSdkLocation;
import com.facebook.buck.apple.toolchain.AppleToolchainProvider;
import com.facebook.buck.apple.toolchain.CodeSignIdentityStore;
import com.facebook.buck.apple.toolchain.ProvisioningProfileStore;
import com.facebook.buck.apple.toolchain.impl.AppleCxxPlatformsProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleDeveloperDirectoryForTestsProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleDeveloperDirectoryProviderFactory;
import com.facebook.buck.apple.toolchain.impl.AppleSdkLocationFactory;
import com.facebook.buck.apple.toolchain.impl.AppleToolchainProviderFactory;
import com.facebook.buck.apple.toolchain.impl.CodeSignIdentityStoreFactory;
import com.facebook.buck.apple.toolchain.impl.ProvisioningProfileStoreFactory;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProviderFactory;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.file.downloader.impl.DownloaderFactory;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.python.toolchain.PexToolProvider;
import com.facebook.buck.python.toolchain.PythonInterpreter;
import com.facebook.buck.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.python.toolchain.impl.PexToolProviderFactory;
import com.facebook.buck.python.toolchain.impl.PythonInterpreterFactory;
import com.facebook.buck.python.toolchain.impl.PythonPlatformsProviderFactory;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.swift.toolchain.SwiftPlatformsProvider;
import com.facebook.buck.swift.toolchain.impl.SwiftPlatformsProviderFactory;
import com.facebook.buck.toolchain.BaseToolchainProvider;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainDescriptor;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainSupplier;
import com.facebook.buck.toolchain.ToolchainWithCapability;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
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
import java.util.stream.Stream;
import org.pf4j.PluginManager;

public class DefaultToolchainProvider extends BaseToolchainProvider {

  ImmutableList<ToolchainDescriptor<?>> DEFAULT_TOOLCHAIN_DESCRIPTORS =
      ImmutableList.of(
          ToolchainDescriptor.of(
              AndroidLegacyToolchain.DEFAULT_NAME,
              AndroidLegacyToolchain.class,
              DefaultAndroidLegacyToolchainFactory.class),
          ToolchainDescriptor.of(
              AndroidSdkLocation.DEFAULT_NAME,
              AndroidSdkLocation.class,
              AndroidSdkLocationFactory.class),
          ToolchainDescriptor.of(
              AndroidNdk.DEFAULT_NAME, AndroidNdk.class, AndroidNdkFactory.class),
          ToolchainDescriptor.of(
              NdkCxxPlatformsProvider.DEFAULT_NAME,
              NdkCxxPlatformsProvider.class,
              NdkCxxPlatformsProviderFactory.class),
          ToolchainDescriptor.of(
              AppleDeveloperDirectoryProvider.DEFAULT_NAME,
              AppleDeveloperDirectoryProvider.class,
              AppleDeveloperDirectoryProviderFactory.class),
          ToolchainDescriptor.of(
              AppleDeveloperDirectoryForTestsProvider.DEFAULT_NAME,
              AppleDeveloperDirectoryForTestsProvider.class,
              AppleDeveloperDirectoryForTestsProviderFactory.class),
          ToolchainDescriptor.of(
              AppleToolchainProvider.DEFAULT_NAME,
              AppleToolchainProvider.class,
              AppleToolchainProviderFactory.class),
          ToolchainDescriptor.of(
              AppleSdkLocation.DEFAULT_NAME, AppleSdkLocation.class, AppleSdkLocationFactory.class),
          ToolchainDescriptor.of(
              AppleCxxPlatformsProvider.DEFAULT_NAME,
              AppleCxxPlatformsProvider.class,
              AppleCxxPlatformsProviderFactory.class),
          ToolchainDescriptor.of(
              CodeSignIdentityStore.DEFAULT_NAME,
              CodeSignIdentityStore.class,
              CodeSignIdentityStoreFactory.class),
          ToolchainDescriptor.of(
              ProvisioningProfileStore.DEFAULT_NAME,
              ProvisioningProfileStore.class,
              ProvisioningProfileStoreFactory.class),
          ToolchainDescriptor.of(
              SwiftPlatformsProvider.DEFAULT_NAME,
              SwiftPlatformsProvider.class,
              SwiftPlatformsProviderFactory.class),
          ToolchainDescriptor.of(
              CxxPlatformsProvider.DEFAULT_NAME,
              CxxPlatformsProvider.class,
              CxxPlatformsProviderFactory.class),
          ToolchainDescriptor.of(
              Downloader.DEFAULT_NAME, Downloader.class, DownloaderFactory.class),
          ToolchainDescriptor.of(
              PexToolProvider.DEFAULT_NAME, PexToolProvider.class, PexToolProviderFactory.class),
          ToolchainDescriptor.of(
              PythonInterpreter.DEFAULT_NAME,
              PythonInterpreter.class,
              PythonInterpreterFactory.class),
          ToolchainDescriptor.of(
              PythonPlatformsProvider.DEFAULT_NAME,
              PythonPlatformsProvider.class,
              PythonPlatformsProviderFactory.class));

  private final ToolchainCreationContext toolchainCreationContext;
  private final ImmutableList<ToolchainDescriptor<?>> toolchainDescriptors;
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
      PluginManager pluginManager,
      ImmutableMap<String, String> environment,
      BuckConfig buckConfig,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor,
      ExecutableFinder executableFinder,
      RuleKeyConfiguration ruleKeyConfiguration) {
    toolchainCreationContext =
        ToolchainCreationContext.of(
            environment,
            buckConfig,
            projectFilesystem,
            processExecutor,
            executableFinder,
            ruleKeyConfiguration);

    toolchainDescriptors = loadToolchainDescriptors(pluginManager);

    ImmutableMap.Builder<String, Class<? extends ToolchainFactory<?>>> toolchainFactoriesBuilder =
        ImmutableMap.builder();
    for (ToolchainDescriptor<?> toolchainDescriptor : toolchainDescriptors) {
      toolchainFactoriesBuilder.put(
          toolchainDescriptor.getName(), toolchainDescriptor.getToolchainFactoryClass());
    }
    toolchainFactories = toolchainFactoriesBuilder.build();
  }

  private ImmutableList<ToolchainDescriptor<?>> loadToolchainDescriptors(
      PluginManager pluginManager) {
    ImmutableList.Builder<ToolchainDescriptor<?>> toolchainDescriptorBuilder =
        ImmutableList.builder();
    toolchainDescriptorBuilder.addAll(DEFAULT_TOOLCHAIN_DESCRIPTORS);
    loadToolchainDescriptorsFromPlugins(pluginManager).forEach(toolchainDescriptorBuilder::add);
    return toolchainDescriptorBuilder.build();
  }

  private Stream<ToolchainDescriptor<?>> loadToolchainDescriptorsFromPlugins(
      PluginManager pluginManager) {
    return pluginManager
        .getExtensions(ToolchainSupplier.class)
        .stream()
        .flatMap(supplier -> supplier.getToolchainDescriptor().stream());
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
  public boolean isToolchainCreated(String toolchainName) {
    return toolchains.getIfPresent(toolchainName) != null;
  }

  @Override
  public <T extends ToolchainWithCapability> Collection<String> getToolchainsWithCapability(
      Class<T> capability) {
    ImmutableList.Builder<String> toolchainsWithCapabilities = ImmutableList.builder();

    for (ToolchainDescriptor<?> toolchainDescriptor : toolchainDescriptors) {
      if (capability.isAssignableFrom(toolchainDescriptor.getToolchainClass())) {
        toolchainsWithCapabilities.add(toolchainDescriptor.getName());
      }
    }

    return toolchainsWithCapabilities.build();
  }

  private Optional<? extends Toolchain> getOrCreate(String toolchainName) {
    try {
      return toolchains.get(toolchainName);
    } catch (ExecutionException | UncheckedExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), HumanReadableException.class);
      throw new HumanReadableException(
          e,
          String.format(
              "Cannot create a toolchain: %s. Cause: %s",
              toolchainName, e.getCause().getMessage()));
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
