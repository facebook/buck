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

package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.MultiPlatform;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * {@link TargetConfigurationTransformer} that transforms a single target configuration with a
 * multiplatform as a target platform to multiple target configurations each containing platforms
 * that are forming multiplatform.
 *
 * <p>For example, a multiplatform "A" contains "B" as a base platform and "X", "Y", "Z" as nested
 * platforms. In this case a target configuration with the "A" platform will be transformed to a set
 * of platforms: "A", "X", "Y", "Z". Note that this transformation doesn't use "B" since the
 * multiplatform uses its base platform to match constraints. Using the multiplatform instead of the
 * base platform allows us to keep the rest of attributes (that don't use transformations)
 * consistent with attributes that use transformations.
 */
public class MultiPlatformTargetConfigurationTransformer implements TargetConfigurationTransformer {

  private final TargetPlatformResolver targetPlatformResolver;

  public MultiPlatformTargetConfigurationTransformer(
      TargetPlatformResolver targetPlatformResolver) {
    this.targetPlatformResolver = targetPlatformResolver;
  }

  @Override
  public ImmutableList<TargetConfiguration> transform(
      TargetConfiguration targetConfiguration, DependencyStack dependencyStack) {
    Platform platform =
        targetPlatformResolver.getTargetPlatform(targetConfiguration, dependencyStack);
    Preconditions.checkState(platform instanceof MultiPlatform, "Not multi platform: %s", platform);
    MultiPlatform multiPlatform = (MultiPlatform) platform;

    ImmutableList.Builder<TargetConfiguration> targetConfigurations =
        ImmutableList.builderWithExpectedSize(multiPlatform.getNestedPlatforms().size() + 1);
    targetConfigurations.add(RuleBasedTargetConfiguration.of(multiPlatform.getBuildTarget()));

    multiPlatform.getNestedPlatforms().stream()
        .map(this::createDefaultTargetConfiguration)
        .forEach(targetConfigurations::add);

    return targetConfigurations.build();
  }

  private TargetConfiguration createDefaultTargetConfiguration(Platform platform) {
    Preconditions.checkState(
        platform instanceof ConstraintBasedPlatform, "Wrong platform type: %s", platform);
    ConstraintBasedPlatform constraintBasedPlatform = (ConstraintBasedPlatform) platform;
    return RuleBasedTargetConfiguration.of(constraintBasedPlatform.getBuildTarget());
  }

  @Override
  public boolean needsTransformation(
      TargetConfiguration targetConfiguration, DependencyStack dependencyStack) {
    Platform platform =
        targetPlatformResolver.getTargetPlatform(targetConfiguration, dependencyStack);
    return platform instanceof MultiPlatform;
  }
}
