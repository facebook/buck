/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.android.toolchain.platform;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.rules.platform.ImmutableMultiPlatformRule;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

/**
 * A description for {@code android_platform}.
 *
 * <p>For example:
 *
 * <pre>
 *   android_platform(
 *      name = "platform",
 *      base_platform = "//config/platform:android",
 *      native_platforms = [
 *          "//config/platform:cpu-x86_64",
 *          "//config/platform:cpu-armv7",
 *      ]
 *   )
 * </pre>
 */
public class AndroidPlatformDescription
    implements ConfigurationRuleDescription<AndroidPlatformArg> {

  @Override
  public Class<AndroidPlatformArg> getConstructorArgType() {
    return AndroidPlatformArg.class;
  }

  @Override
  public ConfigurationRule createConfigurationRule(
      ConfigurationRuleResolver configurationRuleResolver,
      BuildTarget buildTarget,
      AndroidPlatformArg arg) {
    return new ImmutableMultiPlatformRule(
        buildTarget,
        arg.getName(),
        ConfigurationBuildTargets.convert(arg.getBasePlatform()),
        ConfigurationBuildTargets.convert(arg.getNativePlatforms()));
  }

  @Override
  public ImmutableSet<BuildTarget> getConfigurationDeps(AndroidPlatformArg arg) {
    return ImmutableSet.<BuildTarget>builder()
        .add(ConfigurationBuildTargets.convert(arg.getBasePlatform()))
        .addAll(ConfigurationBuildTargets.convert(arg.getNativePlatforms()))
        .build();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidPlatformArg {
    String getName();

    UnconfiguredBuildTargetView getBasePlatform();

    @Value.NaturalOrder
    ImmutableSortedSet<UnconfiguredBuildTargetView> getNativePlatforms();
  }
}
