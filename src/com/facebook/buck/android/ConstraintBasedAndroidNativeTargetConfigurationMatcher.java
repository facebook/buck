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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.NamedPlatform;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/**
 * Matcher that uses constraints to figure out whether platform in target configuration matches the
 * CPU type.
 *
 * <p>It keeps a mapping that provides a list of constraint values for a given CPU type. When a
 * request is made, it uses the constraints for the given CPU and checks whether the platform of the
 * target matches the constraints.
 */
public class ConstraintBasedAndroidNativeTargetConfigurationMatcher
    implements AndroidNativeTargetConfigurationMatcher {

  private final ConfigurationRuleRegistry configurationRuleRegistry;
  private final ImmutableMap<TargetCpuType, NamedPlatform> targetCpuTypeToConstraints;

  public ConstraintBasedAndroidNativeTargetConfigurationMatcher(
      ConfigurationRuleRegistry configurationRuleRegistry,
      ImmutableMap<TargetCpuType, NamedPlatform> targetCpuTypeToConstraints) {
    this.configurationRuleRegistry = configurationRuleRegistry;
    this.targetCpuTypeToConstraints = targetCpuTypeToConstraints;
  }

  @Override
  public boolean nativeTargetConfigurationMatchesCpuType(
      BuildTarget buildTarget, TargetCpuType targetCpuType) {
    NamedPlatform cpuPlatform = targetCpuTypeToConstraints.get(targetCpuType);
    if (cpuPlatform == null) {
      throw new HumanReadableException(
          "%s has inconsistent information about CPU type constraints: %s is not present.",
          buildTarget, targetCpuType);
    }

    Optional<BuildTarget> buildTargetConfigurationTargets =
        buildTarget.getTargetConfiguration().getConfigurationTarget();
    // Platforms are always configured if we get here
    Preconditions.checkState(buildTargetConfigurationTargets.isPresent());

    return cpuPlatform.getBuildTarget().equals(buildTargetConfigurationTargets.get());
  }

  public ConfigurationRuleRegistry getConfigurationRuleRegistry() {
    return configurationRuleRegistry;
  }
}
