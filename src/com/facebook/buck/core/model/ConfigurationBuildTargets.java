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

package com.facebook.buck.core.model;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Encapsulates logic to convert {@link UnconfiguredBuildTarget} that represents a configuration
 * target to an instance of {@link BuildTarget}.
 *
 * <p>We represent configuration targets as {@link BuildTarget} (targets with a configuration) as
 * oppose to {@link UnconfiguredBuildTarget} to keep target graph model consistent and in one place
 * ({@link com.facebook.buck.core.model.targetgraph.TargetGraph} uses {@link BuildTarget} only).
 * This also allows us to avoid adding special handling for unconfigured targets in many other
 * places (for example, in places with generic graph analysis like in {@code query} command).
 */
public class ConfigurationBuildTargets {

  private ConfigurationBuildTargets() {}

  /** @return {@link BuildTarget} that corresponds to a given {@link UnconfiguredBuildTarget}. */
  public static BuildTarget convert(UnconfiguredBuildTarget buildTarget) {
    return buildTarget.configure(ConfigurationForConfigurationTargets.INSTANCE);
  }

  /**
   * Performs conversion similar to {@link #convert(UnconfiguredBuildTarget)} for an optional value.
   */
  public static Optional<BuildTarget> convert(Optional<UnconfiguredBuildTarget> buildTarget) {
    return buildTarget.map(ConfigurationBuildTargets::convert);
  }

  /**
   * Performs conversion similar to {@link #convert(UnconfiguredBuildTarget)} for a set of targets.
   */
  public static ImmutableSet<BuildTarget> convert(
      ImmutableSet<UnconfiguredBuildTarget> buildTargets) {
    return buildTargets.stream()
        .map(ConfigurationBuildTargets::convert)
        .collect(ImmutableSet.toImmutableSet());
  }

  /** Applies conversion similar to {@link #convert(UnconfiguredBuildTarget)} to values in a map. */
  public static <T> ImmutableMap<T, BuildTarget> convertValues(
      ImmutableMap<T, UnconfiguredBuildTarget> map) {
    return map.entrySet().stream()
        .collect(
            ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> convert(entry.getValue())));
  }

  /** Applies conversion similar to {@link #convert(UnconfiguredBuildTarget)} to values in a map. */
  public static <T extends Comparable<T>> ImmutableSortedMap<T, BuildTarget> convertValues(
      ImmutableSortedMap<T, UnconfiguredBuildTarget> map) {
    return map.entrySet().stream()
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                Comparator.naturalOrder(), Map.Entry::getKey, entry -> convert(entry.getValue())));
  }
}
