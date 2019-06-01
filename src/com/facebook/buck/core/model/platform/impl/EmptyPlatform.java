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

import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import java.util.Collection;

/**
 * A platform that doesn't match any constraints.
 *
 * <p>Note that this platform matches an empty list of constraints. The behavior of this platform is
 * equivalent to {@link ConstraintBasedPlatform} with an empty list of constraints.
 *
 * <p>Can be used in places that don't have enough information about constraints.
 */
public class EmptyPlatform implements Platform {

  public static final EmptyPlatform INSTANCE = new EmptyPlatform();

  private final int hashCode = EmptyPlatform.class.getName().hashCode();

  private EmptyPlatform() {}

  @Override
  public boolean matchesAll(Collection<ConstraintValue> constraintValues) {
    return constraintValues.isEmpty();
  }

  @Override
  public String toString() {
    return "EmptyPlatform";
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof EmptyPlatform;
  }
}
