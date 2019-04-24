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
package com.facebook.buck.core.model.platform.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.util.environment.Architecture;
import java.util.Optional;
import org.junit.Test;

public class CpuConstraintDetectorTest {

  @Test
  public void testMatchingCpuArchitectureReturnsTrue() {
    CpuConstraintDetector cpuConstraintDetector = new CpuConstraintDetector(Architecture.X86_64);

    assertTrue(
        cpuConstraintDetector.matchesHost(
            ConstraintValue.of(
                UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:x86_64"),
                ConstraintSetting.of(
                    UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:cpu"),
                    Optional.of(cpuConstraintDetector)))));
  }

  @Test
  public void testNonMatchingCpuArchitectureReturnsFalse() {
    CpuConstraintDetector cpuConstraintDetector = new CpuConstraintDetector(Architecture.X86_64);

    assertFalse(
        cpuConstraintDetector.matchesHost(
            ConstraintValue.of(
                UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:i386"),
                ConstraintSetting.of(
                    UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:cpu"),
                    Optional.of(cpuConstraintDetector)))));
  }
}
