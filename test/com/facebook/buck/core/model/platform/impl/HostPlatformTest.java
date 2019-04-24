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
import com.facebook.buck.core.model.platform.HostConstraintDetector;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;

public class HostPlatformTest {

  @Test
  public void testMatchesAllReturnsTrueWithConstraintWithoutDetector() {
    ConstraintSetting cs1 =
        ConstraintSetting.of(
            UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:cs1"), Optional.empty());
    ConstraintValue cs1v1 =
        ConstraintValue.of(UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:cs1v1"), cs1);

    assertFalse(HostPlatform.INSTANCE.matchesAll(Collections.singleton(cs1v1)));
  }

  @Test
  public void testMatchesAllReturnsTrueWithAllConstraintsMatching() {
    ConstraintSetting cs1 =
        ConstraintSetting.of(
            UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:cs1"),
            Optional.of(value -> true));
    ConstraintValue cs1v1 =
        ConstraintValue.of(UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:cs1v1"), cs1);

    assertTrue(HostPlatform.INSTANCE.matchesAll(Collections.singleton(cs1v1)));
  }

  @Test
  public void testMatchesAllReturnsFalseWithConstraintsNotMatching() {
    HostConstraintDetector detector = value -> "host".equals(value.getBuildTarget().getBaseName());

    ConstraintSetting cs1 =
        ConstraintSetting.of(
            UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:cs1"), Optional.of(detector));
    ConstraintValue cs1v1 =
        ConstraintValue.of(UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:host"), cs1);

    ConstraintSetting cs2 =
        ConstraintSetting.of(
            UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:cs2"), Optional.of(detector));
    ConstraintValue cs2v1 =
        ConstraintValue.of(
            UnconfiguredBuildTargetFactoryForTests.newInstance("//cs:non-host"), cs2);

    assertFalse(HostPlatform.INSTANCE.matchesAll(Arrays.asList(cs1v1, cs2v1)));
  }

  @Test
  public void testMatchesAllReturnsTrueWithEmptyConstraints() {
    assertTrue(HostPlatform.INSTANCE.matchesAll(Collections.emptyList()));
  }
}
