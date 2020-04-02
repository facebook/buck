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

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableSet;

/** Macro that resolves to the output location of a build rule. */
@BuckStyleValue
public abstract class UnconfiguredLocationPlatformMacro extends UnconfiguredBaseLocationMacro {

  @Override
  public abstract UnconfiguredBuildTargetWithOutputs getTargetWithOutputs();

  public static UnconfiguredLocationPlatformMacro of(
      UnconfiguredBuildTargetWithOutputs target, Iterable<? extends Flavor> flavors) {
    return ImmutableUnconfiguredLocationPlatformMacro.ofImpl(target, flavors);
  }

  abstract ImmutableSet<Flavor> getFlavors();

  @Override
  public LocationPlatformMacro configure(
      TargetConfiguration targetConfiguration, TargetConfiguration hostConfiguration) {
    return LocationPlatformMacro.of(
        getTargetWithOutputs().configure(targetConfiguration), getFlavors());
  }
}
