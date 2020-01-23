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

import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;
import javax.annotation.Nullable;

/** Macro that resolves to the output location of a build rule. */
@BuckStyleValue
public abstract class LocationPlatformMacro extends BaseLocationMacro {

  @Override
  public abstract BuildTargetWithOutputs getTargetWithOutputs();

  @Override
  public Class<? extends Macro> getMacroClass() {
    return LocationPlatformMacro.class;
  }

  @Override
  protected LocationPlatformMacro withTargetWithOutputs(BuildTargetWithOutputs target) {
    return of(target, getFlavors());
  }

  public static LocationPlatformMacro of(
      BuildTargetWithOutputs target, Iterable<? extends Flavor> flavors) {
    return ImmutableLocationPlatformMacro.of(target, flavors);
  }

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
}
