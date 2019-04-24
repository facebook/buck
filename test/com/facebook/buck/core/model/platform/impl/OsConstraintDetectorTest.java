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
import com.facebook.buck.util.environment.Platform;
import java.util.Optional;
import org.junit.Test;

public class OsConstraintDetectorTest {

  @Test
  public void testMatchingOsNameReturnsTrue() {
    OsConstraintDetector osConstraintDetector = new OsConstraintDetector(Platform.MACOS);

    assertTrue(
        osConstraintDetector.matchesHost(
            ConstraintValue.of(
                UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:osx"),
                ConstraintSetting.of(
                    UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:os"),
                    Optional.of(osConstraintDetector)))));
  }

  @Test
  public void testNonMatchingOsNameReturnsFalse() {
    OsConstraintDetector osConstraintDetector = new OsConstraintDetector(Platform.WINDOWS);

    assertFalse(
        osConstraintDetector.matchesHost(
            ConstraintValue.of(
                UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:osx"),
                ConstraintSetting.of(
                    UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:os"),
                    Optional.of(osConstraintDetector)))));
  }
}
