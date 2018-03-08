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

import com.facebook.buck.toolchain.BaseToolchainProvider;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.toolchain.ToolchainInstantiationException;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.ToolchainWithCapability;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ToolchainProviderBuilder {
  private final Map<String, Toolchain> toolchains = new HashMap<>();

  public ToolchainProviderBuilder() {}

  public ToolchainProviderBuilder withToolchain(NamedToolchain toolchain) {
    toolchains.put(toolchain.getName(), toolchain.getToolchain());
    return this;
  }

  public ToolchainProviderBuilder withToolchain(String name, Toolchain toolchain) {
    toolchains.put(name, toolchain);
    return this;
  }

  public ToolchainProvider build() {
    return new SimpleToolchainProvider(toolchains);
  }

  private static class SimpleToolchainProvider extends BaseToolchainProvider {
    private final ImmutableMap<String, Toolchain> toolchains;

    private SimpleToolchainProvider(Map<String, Toolchain> toolchains) {
      this.toolchains = ImmutableMap.copyOf(toolchains);
    }

    @Override
    public Toolchain getByName(String toolchainName) {
      return toolchains.get(toolchainName);
    }

    @Override
    public boolean isToolchainPresent(String toolchainName) {
      return toolchains.containsKey(toolchainName);
    }

    @Override
    public boolean isToolchainCreated(String toolchainName) {
      return isToolchainPresent(toolchainName);
    }

    @Override
    public boolean isToolchainFailed(String toolchainName) {
      return false;
    }

    @Override
    public <T extends ToolchainWithCapability> Collection<String> getToolchainsWithCapability(
        Class<T> capability) {
      ImmutableList.Builder<String> featureSupportingToolchains = ImmutableList.builder();

      for (Map.Entry<String, Toolchain> toolchainEntry : toolchains.entrySet()) {
        if (capability.isAssignableFrom(toolchainEntry.getValue().getClass())) {
          featureSupportingToolchains.add(toolchainEntry.getKey());
        }
      }

      return featureSupportingToolchains.build();
    }

    @Override
    public Optional<ToolchainInstantiationException> getToolchainInstantiationException(
        String toolchainName) {
      return Optional.empty();
    }
  }
}
