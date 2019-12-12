/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.model.platform.impl;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import java.util.Collection;
import java.util.Objects;

/**
 * A platform that doesn't match any constraints.
 *
 * <p>Note that this platform matches an empty list of constraints. The behavior of this platform is
 * equivalent to {@link ConstraintBasedPlatform} with an empty list of constraints.
 *
 * <p>Can be used in places when some platform is needed, but {@link UnconfiguredPlatform} does not
 * work because it if effectively prohibits using platforms.
 */
public class EmptyPlatform implements Platform {

  public static final EmptyPlatform INSTANCE = new EmptyPlatform();

  private final int hashCode = Objects.hash(EmptyPlatform.class);

  private EmptyPlatform() {}

  @Override
  public boolean matchesAll(
      Collection<ConstraintValue> constraintValues, DependencyStack dependencyStack) {
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
