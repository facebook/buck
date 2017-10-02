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

import com.facebook.buck.android.toolchain.AndroidToolchain;
import com.facebook.buck.android.toolchain.impl.DefaultAndroidToolchainFactory;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.toolchain.BaseToolchainProvider;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;

public class DefaultToolchainProvider extends BaseToolchainProvider {

  enum ToolchainDescriptor {
    ANDROID(AndroidToolchain.DEFAULT_NAME, DefaultAndroidToolchainFactory.class);

    @VisibleForTesting final String name;
    private final Class<? extends ToolchainFactory<?>> toolchainFactoryClass;

    ToolchainDescriptor(String name, Class<? extends ToolchainFactory<?>> toolchainFactoryClass) {
      this.name = name;
      this.toolchainFactoryClass = toolchainFactoryClass;
    }
  }

  private final ImmutableMap<String, String> environment;
  private final BuckConfig buckConfig;
  private final ProjectFilesystem projectFilesystem;
  private final ImmutableMap<String, Class<? extends ToolchainFactory<?>>> toolchainFactories;

  private final Map<String, Toolchain> toolchains = new HashMap<>();

  public DefaultToolchainProvider(
      ImmutableMap<String, String> environment,
      BuckConfig buckConfig,
      ProjectFilesystem projectFilesystem) {
    this.environment = environment;
    this.buckConfig = buckConfig;
    this.projectFilesystem = projectFilesystem;

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
    if (toolchains.containsKey(toolchainName)) {
      return toolchains.get(toolchainName);
    }

    if (!toolchainFactories.containsKey(toolchainName)) {
      throw new IllegalStateException("Unknown toolchain: " + toolchainName);
    }
    Class<? extends ToolchainFactory<?>> toolchainFactoryClass =
        toolchainFactories.get(toolchainName);
    Toolchain toolchain = createToolchain(toolchainFactoryClass);
    toolchains.put(toolchainName, toolchain);

    return toolchain;
  }

  private Toolchain createToolchain(Class<? extends ToolchainFactory<?>> toolchainFactoryClass) {
    ToolchainFactory<?> toolchainFactory;
    try {
      toolchainFactory = toolchainFactoryClass.newInstance();
    } catch (IllegalAccessException | InstantiationException e) {
      throw new RuntimeException(e);
    }
    return toolchainFactory.createToolchain(environment, buckConfig, projectFilesystem);
  }
}
