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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.model.platform.Platform;
import java.util.Collection;
import java.util.Objects;

/**
 * A platform that fails when constraints are checked.
 *
 * <p>Used by default when platform is not specified.
 */
public class UnconfiguredPlatform implements Platform {

  public static final UnconfiguredPlatform INSTANCE = new UnconfiguredPlatform();

  private final int hashCode = Objects.hash(UnconfiguredPlatform.class.getName());

  private UnconfiguredPlatform() {}

  @Override
  public boolean matchesAll(
      Collection<ConstraintValue> constraintValues, DependencyStack dependencyStack) {
    throw new HumanReadableException(
        dependencyStack, "Cannot use select() expression when target platform is not specified");
  }

  @Override
  public String toString() {
    return "UnconfiguredPlatform";
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof UnconfiguredPlatform;
  }
}
