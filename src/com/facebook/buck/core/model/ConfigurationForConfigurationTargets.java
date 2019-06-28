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
package com.facebook.buck.core.model;

import com.google.common.collect.ImmutableSet;

/**
 * Special configuration that is used together with {@link UnconfiguredBuildTarget} that represent
 * configuration targets in order to form {@link BuildTarget}.
 */
public class ConfigurationForConfigurationTargets implements TargetConfiguration {
  public static final ConfigurationForConfigurationTargets INSTANCE =
      new ConfigurationForConfigurationTargets();

  private final int hashCode = ConfigurationForConfigurationTargets.class.getName().hashCode();

  private ConfigurationForConfigurationTargets() {}

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof ConfigurationForConfigurationTargets;
  }

  @Override
  public ImmutableSet<BuildTarget> getConfigurationTargets() {
    return ImmutableSet.of();
  }
}
