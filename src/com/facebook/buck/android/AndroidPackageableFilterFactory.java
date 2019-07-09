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

import com.facebook.buck.android.packageable.AndroidPackageableFilter;
import com.facebook.buck.android.packageable.ConstraintBasedAndroidPackageableFilter;
import com.facebook.buck.android.packageable.NoopAndroidPackageableFilter;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.impl.MultiPlatform;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.google.common.collect.ImmutableSet;

/**
 * Factory to create {@link AndroidPackageableFilter} using an instance of {@link
 * AndroidNativeTargetConfigurationMatcher}. These two are closely related, but have different
 * interface. The factory is used to emphasize relationships between instances of these two
 * interfaces.
 */
class AndroidPackageableFilterFactory {

  /**
   * Creates {@link AndroidPackageableFilter} using the configuration of the provided build target
   * as a configuration for non-native targets and an instance of {@link
   * AndroidNativeTargetConfigurationMatcher}.
   */
  public static AndroidPackageableFilter createFromConfigurationMatcher(
      BuildTarget buildTarget, AndroidNativeTargetConfigurationMatcher configurationMatcher) {
    if (configurationMatcher instanceof NoopAndroidNativeTargetConfigurationMatcher) {
      return new NoopAndroidPackageableFilter();
    }
    ConstraintBasedAndroidNativeTargetConfigurationMatcher
        constraintBasedAndroidNativeTargetConfigurationMatcher =
            (ConstraintBasedAndroidNativeTargetConfigurationMatcher) configurationMatcher;

    return new ConstraintBasedAndroidPackageableFilter(
        constraintBasedAndroidNativeTargetConfigurationMatcher.getConfigurationRuleRegistry(),
        buildTarget.getTargetConfiguration(),
        constraintBasedAndroidNativeTargetConfigurationMatcher
            .getTargetCpuTypeToConstraints()
            .values());
  }

  /**
   * Creates {@link AndroidPackageableFilter} using the configuration of the provided build target
   * as a configuration for non-native targets. Native targets will be filtered out by this filter.
   */
  public static AndroidPackageableFilter createForNonNativeTargets(
      ConfigurationRuleRegistry configurationRuleRegistry, BuildTarget buildTarget) {
    TargetConfiguration targetConfiguration = buildTarget.getTargetConfiguration();
    Platform platform =
        configurationRuleRegistry
            .getTargetPlatformResolver()
            .getTargetPlatform(targetConfiguration);

    if (!(platform instanceof MultiPlatform)) {
      return new NoopAndroidPackageableFilter();
    }

    return new ConstraintBasedAndroidPackageableFilter(
        configurationRuleRegistry, targetConfiguration, ImmutableSet.of());
  }
}
