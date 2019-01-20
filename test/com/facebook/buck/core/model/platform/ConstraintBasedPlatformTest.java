/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.model.platform;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.google.common.collect.ImmutableSet;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ConstraintBasedPlatformTest {

  private final ConstraintSetting cs1 =
      ConstraintSetting.of(BuildTargetFactory.newInstance("//cs:cs1"));
  private final ConstraintValue cs1v1 =
      ConstraintValue.of(BuildTargetFactory.newInstance("//cs:cs1v1"), cs1);

  private final ConstraintSetting cs2 =
      ConstraintSetting.of(BuildTargetFactory.newInstance("//cs:cs2"));
  private final ConstraintValue cs2v1 =
      ConstraintValue.of(BuildTargetFactory.newInstance("//cs:cs2v1"), cs2);

  private final ConstraintSetting cs3 =
      ConstraintSetting.of(BuildTargetFactory.newInstance("//cs:cs3"));
  private final ConstraintValue cs3v1 =
      ConstraintValue.of(BuildTargetFactory.newInstance("//cs:cs3v1"), cs3);

  @Test
  public void testMatchesAllReturnsTrueForSubsetOfConstraints() {
    ConstraintBasedPlatform platform =
        new ConstraintBasedPlatform(ImmutableSet.of(cs1v1, cs2v1, cs3v1));

    assertTrue(platform.matchesAll(Arrays.asList(cs1v1, cs2v1)));
  }

  @Test
  public void testMatchesAllReturnsTrueForAllOfConstraints() {
    ConstraintBasedPlatform platform =
        new ConstraintBasedPlatform(ImmutableSet.of(cs1v1, cs2v1, cs3v1));

    assertTrue(platform.matchesAll(Arrays.asList(cs1v1, cs2v1, cs3v1)));
  }

  @Test
  public void testMatchesAllReturnsTrueForEmptyConstraints() {
    ConstraintBasedPlatform platform =
        new ConstraintBasedPlatform(ImmutableSet.of(cs1v1, cs2v1, cs3v1));

    assertTrue(platform.matchesAll(Collections.emptyList()));
  }

  @Test
  public void testMatchesAllReturnsFalseForUnknownConstraints() {
    ConstraintBasedPlatform platform = new ConstraintBasedPlatform(ImmutableSet.of(cs1v1, cs2v1));

    assertFalse(platform.matchesAll(Arrays.asList(cs1v1, cs2v1, cs3v1)));
  }
}
