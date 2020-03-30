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

package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;

/** Constraint resolver that always throws. Can be used in tests or in unconfigurable targets. */
public class ThrowingConstraintResolver implements ConstraintResolver {
  /** Unconditionally throw */
  @Override
  public ConstraintSetting getConstraintSetting(
      BuildTarget buildTarget, DependencyStack dependencyStack) {
    throw new UnsupportedOperationException();
  }

  /** Unconditionally throw */
  @Override
  public ConstraintValue getConstraintValue(
      BuildTarget buildTarget, DependencyStack dependencyStack) {
    throw new UnsupportedOperationException();
  }
}
