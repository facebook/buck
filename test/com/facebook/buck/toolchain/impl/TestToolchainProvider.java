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

import com.facebook.buck.android.toolchain.NdkCxxPlatformsProvider;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.toolchain.BaseToolchainProvider;
import com.facebook.buck.toolchain.Toolchain;
import com.facebook.buck.toolchain.ToolchainWithCapability;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class TestToolchainProvider extends BaseToolchainProvider {
  private final Map<String, Toolchain> toolchains = new HashMap<>();

  public TestToolchainProvider() {
    addToolchain(
        NdkCxxPlatformsProvider.DEFAULT_NAME, NdkCxxPlatformsProvider.of(ImmutableMap.of()));
    addToolchain(
        AppleCxxPlatformsProvider.DEFAULT_NAME,
        AppleCxxPlatformsProvider.of(
            FlavorDomain.from("Apple C++ Platform", Collections.emptyList())));

    CxxPlatform defaultCxxPlatform =
        DefaultCxxPlatforms.build(
            Platform.detect(), new CxxBuckConfig(FakeBuckConfig.builder().build()));
    addToolchain(
        CxxPlatformsProvider.DEFAULT_NAME,
        CxxPlatformsProvider.of(
            defaultCxxPlatform,
            new FlavorDomain<>(
                "C/C++ platform",
                ImmutableMap.of(DefaultCxxPlatforms.FLAVOR, defaultCxxPlatform))));
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

  public void addToolchain(String name, Toolchain toolchain) {
    toolchains.put(name, toolchain);
  }
}
