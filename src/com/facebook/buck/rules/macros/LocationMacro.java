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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;

/** Macro that resolves to the output location of a build rule. */
@BuckStyleValue
public abstract class LocationMacro extends BaseLocationMacro {

  @Override
  public Class<? extends Macro> getMacroClass() {
    return LocationMacro.class;
  }

  @Override
  protected LocationMacro withTarget(BuildTarget target) {
    return ImmutableLocationMacro.of(target, getSupplementaryOutputIdentifier());
  }

  /** Shorthand for constructing a LocationMacro referring to the main output. */
  public static LocationMacro of(BuildTarget buildTarget) {
    return of(buildTarget, Optional.empty());
  }

  public static LocationMacro of(
      BuildTarget target, Optional<String> supplementaryOutputIdentifier) {
    return ImmutableLocationMacro.of(target, supplementaryOutputIdentifier);
  }
}
