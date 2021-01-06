/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.android.toolchain.platform.AndroidMultiPlatform;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.NamedPlatform;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.google.common.collect.ImmutableMap;
import java.util.Set;

/**
 * Creates {@link AndroidNativeTargetConfigurationMatcher} using given CPU types and CPU type to
 * constraint map.
 */
class AndroidNativeTargetConfigurationMatcherFactory {
  private AndroidNativeTargetConfigurationMatcherFactory() {}

  /**
   * Creates {@link AndroidNativeTargetConfigurationMatcher} using given attributes of Android
   * binary.
   *
   * <p>When used in a context without {@code android_platform} creates a default instance that
   * provides legacy logic.
   *
   * @param buildTarget build target of Android binary. This target is used to get access to the
   *     configuration.
   * @param cpuFilters a set of CPU types this Android binary is built for.
   */
  public static AndroidNativeTargetConfigurationMatcher create(
      ConfigurationRuleRegistry configurationRuleRegistry,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      Set<TargetCpuType> cpuFilters) {
    Platform platform =
        configurationRuleRegistry
            .getTargetPlatformResolver()
            .getTargetPlatform(buildTarget.getTargetConfiguration(), dependencyStack);
    if (!(platform instanceof AndroidMultiPlatform)) {
      return new NoopAndroidNativeTargetConfigurationMatcher();
    }

    ImmutableMap.Builder<TargetCpuType, NamedPlatform> result = ImmutableMap.builder();
    AndroidMultiPlatform multiPlatform = (AndroidMultiPlatform) platform;
    for (TargetCpuType cpuType : cpuFilters) {
      result.put(cpuType, createAndVerifyConstraintValues(buildTarget, multiPlatform, cpuType));
    }

    return new ConstraintBasedAndroidNativeTargetConfigurationMatcher(
        configurationRuleRegistry, result.build());
  }

  private static NamedPlatform createAndVerifyConstraintValues(
      BuildTarget buildTarget, AndroidMultiPlatform multiPlatform, TargetCpuType cpuType) {

    NamedPlatform nativePlatform = multiPlatform.getNestedPlatformsByCpuType().get(cpuType);
    if (nativePlatform == null) {
      throw new HumanReadableException(
          "%s: nested platform is not found for %s CPU type in platform %s",
          buildTarget, cpuType, multiPlatform.getBuildTarget());
    }

    return nativePlatform;
  }
}
