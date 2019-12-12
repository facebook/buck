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
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Macro that resolves to the output location of a build rule. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractLocationPlatformMacro extends BaseLocationMacro {

  @Override
  public abstract BuildTarget getTarget();

  @Override
  abstract Optional<String> getSupplementaryOutputIdentifier();

  abstract ImmutableSet<Flavor> getFlavors();

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), getFlavors());
  }

  @Override
  public boolean equals(@Nullable Object another) {
    if (this == another) {
      return true;
    }
    if (another == null || this.getClass() != another.getClass()) {
      return false;
    }
    LocationPlatformMacro anotherLocationMacro = (LocationPlatformMacro) another;
    return super.equals(anotherLocationMacro)
        && Objects.equals(this.getFlavors(), ((LocationPlatformMacro) another).getFlavors());
  }

  /** Shorthand for constructing a LocationMacro referring to the main output. */
  @VisibleForTesting
  public static LocationPlatformMacro of(BuildTarget buildTarget, ImmutableSet<Flavor> flavors) {
    return LocationPlatformMacro.of(buildTarget, Optional.empty(), flavors);
  }
}
