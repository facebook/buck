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
package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.platform.impl.CpuConstraintDetector;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/** Description for {@code cpu_constraint_detector}. */
public class CpuConstraintDetectorDescription
    implements ConfigurationRuleDescription<CpuConstraintDetectorArg> {

  @Override
  public ConfigurationRule createConfigurationRule(
      ConfigurationRuleResolver configurationRuleResolver,
      Cell cell,
      UnconfiguredBuildTargetView buildTarget,
      CpuConstraintDetectorArg arg) {
    return new CpuConstraintDetectorRule(buildTarget, new CpuConstraintDetector());
  }

  @Override
  public Class<CpuConstraintDetectorArg> getConstructorArgType() {
    return CpuConstraintDetectorArg.class;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCpuConstraintDetectorArg {
    String getName();
  }
}
