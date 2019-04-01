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

import com.google.common.collect.ImmutableSet;
import java.util.Collection;

/** An implementation of a {@link Platform} that has a fixed set of constraints. */
public class ConstraintBasedPlatform implements Platform {
  private final String platformName;
  private final ImmutableSet<ConstraintValue> constraintValues;

  public ConstraintBasedPlatform(
      String platformName, ImmutableSet<ConstraintValue> constraintValues) {
    this.platformName = platformName;
    this.constraintValues = constraintValues;
  }

  /**
   * A platform matches the given constraints when these constraints are present in the platform
   * constraints (platform constraints is a superset of the provided constraints).
   */
  @Override
  public boolean matchesAll(Collection<ConstraintValue> constraintValues) {
    return this.constraintValues.containsAll(constraintValues);
  }

  @Override
  public String toString() {
    return platformName;
  }

  public ImmutableSet<ConstraintValue> getConstraintValues() {
    return constraintValues;
  }
}
