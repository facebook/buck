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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.toolchain.BaseToolchainProvider;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainDescriptor;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainInstantiationException;
import com.facebook.buck.toolchain.ToolchainSupplier;
import com.facebook.buck.toolchain.ToolchainWithCapability;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.pf4j.PluginManager;

public class DefaultToolchainProvider extends BaseToolchainProvider {

  private static final Logger LOG = Logger.get(DefaultToolchainProvider.class);

  private final ToolchainCreationContext toolchainCreationContext;
  private final ImmutableList<ToolchainDescriptor<?>> toolchainDescriptors;
  private final ImmutableMap<String, Class<? extends ToolchainFactory<?>>> toolchainFactories;

  private final LoadingCache<String, Optional<? extends Toolchain>> toolchains =
      CacheBuilder.newBuilder()
          .maximumSize(1024)
          .build(
              new CacheLoader<String, Optional<? extends Toolchain>>() {
                @Override
                public Optional<? extends Toolchain> load(String toolchainName) {
                  if (!toolchainFactories.containsKey(toolchainName)) {
                    throw new IllegalStateException("Unknown toolchain: " + toolchainName);
                  }
                  return createToolchain(toolchainFactories.get(toolchainName));
                }
              });
  private final ConcurrentHashMap<String, ToolchainInstantiationException> failedToolchains =
      new ConcurrentHashMap<>();

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

    toolchainDescriptors =
        loadToolchainDescriptorsFromPlugins(pluginManager).collect(ImmutableList.toImmutableList());

    ImmutableMap.Builder<String, Class<? extends ToolchainFactory<?>>> toolchainFactoriesBuilder =
        ImmutableMap.builder();
    for (ToolchainDescriptor<?> toolchainDescriptor : toolchainDescriptors) {
      toolchainFactoriesBuilder.put(
          toolchainDescriptor.getName(), toolchainDescriptor.getToolchainFactoryClass());
    }
    toolchainFactories = toolchainFactoriesBuilder.build();
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
      ToolchainInstantiationException exception;
      if (failedToolchains.containsKey(toolchainName)) {
        exception = failedToolchains.get(toolchainName);
      } else {
        exception = new ToolchainInstantiationException("Unknown toolchain: %s", toolchainName);
      }
      throw exception;
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
  public boolean isToolchainFailed(String toolchainName) {
    return failedToolchains.containsKey(toolchainName);
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

  @Override
  public Optional<ToolchainInstantiationException> getToolchainInstantiationException(
      String toolchainName) {
    return failedToolchains.containsKey(toolchainName)
        ? Optional.of(failedToolchains.get(toolchainName))
        : Optional.empty();
  }

  private Optional<? extends Toolchain> getOrCreate(String toolchainName) {
    if (failedToolchains.containsKey(toolchainName)) {
      return Optional.empty();
    }

    try {
      return toolchains.get(toolchainName);
    } catch (ExecutionException | UncheckedExecutionException e) {
      if (e.getCause() instanceof ToolchainInstantiationException) {
        LOG.warn(
            String.format(
                "Cannot create a toolchain: %s. Cause: %s",
                toolchainName, e.getCause().getMessage()));
        failedToolchains.put(toolchainName, (ToolchainInstantiationException) e.getCause());
        return Optional.empty();
      }
      throw new RuntimeException(
          String.format(
              "Cannot create a toolchain: %s. Cause: %s", toolchainName, e.getCause().getMessage()),
          e);
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
