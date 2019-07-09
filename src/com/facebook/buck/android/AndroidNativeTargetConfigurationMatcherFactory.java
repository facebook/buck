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
package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.impl.MultiPlatform;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.google.common.collect.ImmutableList;
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
   * @param targetCpuTypeToConstraintTarget a map from CPU type to a corresponding constraint that
   *     is used to identify a platform for that CPU type.
   */
  public static AndroidNativeTargetConfigurationMatcher create(
      ConfigurationRuleRegistry configurationRuleRegistry,
      BuildTarget buildTarget,
      Set<TargetCpuType> cpuFilters,
      ImmutableMap<TargetCpuType, BuildTarget> targetCpuTypeToConstraintTarget) {
    Platform platform =
        configurationRuleRegistry
            .getTargetPlatformResolver()
            .getTargetPlatform(buildTarget.getTargetConfiguration());
    if (!(platform instanceof MultiPlatform)) {
      return new NoopAndroidNativeTargetConfigurationMatcher();
    }

    ImmutableMap.Builder<TargetCpuType, ImmutableList<ConstraintValue>> result =
        ImmutableMap.builder();
    MultiPlatform multiPlatform = (MultiPlatform) platform;
    for (TargetCpuType cpuType : cpuFilters) {
      result.put(
          cpuType,
          createAndVerifyConstraintValues(
              configurationRuleRegistry,
              buildTarget,
              multiPlatform,
              targetCpuTypeToConstraintTarget,
              cpuType));
    }

    return new ConstraintBasedAndroidNativeTargetConfigurationMatcher(
        configurationRuleRegistry, result.build());
  }

  private static ImmutableList<ConstraintValue> createAndVerifyConstraintValues(
      ConfigurationRuleRegistry configurationRuleRegistry,
      BuildTarget buildTarget,
      MultiPlatform multiPlatform,
      ImmutableMap<TargetCpuType, BuildTarget> targetCpuTypeToConstraintTarget,
      TargetCpuType cpuType) {
    if (!targetCpuTypeToConstraintTarget.containsKey(cpuType)) {
      throw new HumanReadableException(
          "%s is missing information about constraint for %s CPU type "
              + "in target_cpu_type_to_constraint",
          buildTarget, cpuType);
    }
    BuildTarget targetCpuConstraint = targetCpuTypeToConstraintTarget.get(cpuType);
    ConstraintValue constraintValue =
        configurationRuleRegistry.getConstraintResolver().getConstraintValue(targetCpuConstraint);
    ImmutableList<ConstraintValue> constraintValues = ImmutableList.of(constraintValue);
    Platform matchingPlatform = null;
    for (Platform nativePlatform : multiPlatform.getNestedPlatforms()) {
      if (nativePlatform.matchesAll(constraintValues)) {
        if (matchingPlatform != null) {
          throw new HumanReadableException(
              "%s contains ambiguous information in target_cpu_type_to_constraint: "
                  + "%s matches both %s and %s",
              buildTarget, targetCpuConstraint, matchingPlatform, nativePlatform);
        }
        matchingPlatform = nativePlatform;
      }
    }

    if (matchingPlatform == null) {
      throw new HumanReadableException(
          "%s contains incomplete information in target_cpu_type_to_constraint: "
              + "%s does not match native platforms %s",
          buildTarget, targetCpuConstraint, multiPlatform.getNestedPlatforms());
    }

    return constraintValues;
  }
}
