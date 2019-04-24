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

import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.platform.HostConstraintDetector;
import com.facebook.buck.core.model.platform.ProvidesHostConstraintDetector;
import com.facebook.buck.core.model.platform.impl.CpuConstraintDetector;
import com.facebook.buck.core.rules.config.ConfigurationRule;

/** A configuration rule that represents {@code cpu_constraint_detector} target. */
public class CpuConstraintDetectorRule
    implements ConfigurationRule, ProvidesHostConstraintDetector {

  private final UnconfiguredBuildTargetView buildTarget;
  private final CpuConstraintDetector cpuConstraintDetector;

  public CpuConstraintDetectorRule(
      UnconfiguredBuildTargetView buildTarget, CpuConstraintDetector cpuConstraintDetector) {
    this.buildTarget = buildTarget;
    this.cpuConstraintDetector = cpuConstraintDetector;
  }

  @Override
  public UnconfiguredBuildTargetView getBuildTarget() {
    return buildTarget;
  }

  @Override
  public HostConstraintDetector getHostConstraintDetector() {
    return cpuConstraintDetector;
  }
}
