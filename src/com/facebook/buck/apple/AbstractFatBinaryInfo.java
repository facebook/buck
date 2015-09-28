/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxInferEnhancer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.immutables.value.Value;

import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

/**
 * Information about a build target that represents a fat binary.
 *
 * Fat binaries are represented by build targets having multiple platform flavors.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractFatBinaryInfo {
  public abstract BuildTarget getFatTarget();
  public abstract ImmutableList<BuildTarget> getThinTargets();

  /**
   * Returns a representative platform for use in retrieving architecture agnostic tools.
   *
   * Platforms are architecture specific, but some tools are architecture agnostic. Since there
   * isn't a concept of target architecture agnostic tools, this simply returns one of the
   * platforms, trusting the caller to only use the architecture agnostic tools.
   */
  public abstract AppleCxxPlatform getRepresentativePlatform();

  /**
   * Inspect the given build target and return information about it if its a fat binary.
   *
   * @return non-empty when the target represents a fat binary.
   * @throws com.facebook.buck.util.HumanReadableException
   *    when the target is a fat binary but has incompatible flavors.
   */
  public static Optional<FatBinaryInfo> create(
      final Map<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      BuildTarget target) {
    ImmutableList<ImmutableSortedSet<Flavor>> thinFlavorSets =
        generateThinFlavors(platformFlavorsToAppleCxxPlatforms.keySet(), target.getFlavors());
    if (thinFlavorSets.size() <= 1) {  // Actually a thin binary
      return Optional.absent();
    }

    if (!Sets.intersection(target.getFlavors(), FORBIDDEN_BUILD_ACTIONS).isEmpty()) {
      throw new HumanReadableException(
          "%s: Fat binaries is only supported when building an actual binary.",
          target);
    }

    Predicate<Flavor> isPlatformFlavor =
        Predicates.in(platformFlavorsToAppleCxxPlatforms.keySet());

    AppleCxxPlatform representativePlatform = null;
    AppleSdk sdk = null;
    for (SortedSet<Flavor> flavorSet : thinFlavorSets) {
      AppleCxxPlatform platform = Preconditions.checkNotNull(
          platformFlavorsToAppleCxxPlatforms.get(Iterables.find(flavorSet, isPlatformFlavor)));
      if (sdk == null) {
        sdk = platform.getAppleSdk();
        representativePlatform = platform;
      } else if (sdk != platform.getAppleSdk()) {
        throw new HumanReadableException(
            "%s: Fat binaries can only be generated from binaries compiled for the same SDK.",
            target);
      }
    }

    FatBinaryInfo.Builder builder =
        FatBinaryInfo.builder()
            .setFatTarget(target)
            .setRepresentativePlatform(Preconditions.checkNotNull(representativePlatform));

    BuildTarget platformFreeTarget =
        target.withoutFlavors(platformFlavorsToAppleCxxPlatforms.keySet());
    for (SortedSet<Flavor> flavorSet : thinFlavorSets) {
      builder.addThinTargets(platformFreeTarget.withFlavors(flavorSet));
    }

    return Optional.of(builder.build());
  }

  /**
   * Expand flavors representing a fat binary into its thin binary equivalents.
   *
   * Useful when dealing with functions unaware of fat binaries.
   *
   * This does not actually check that the particular flavor set is valid.
   */
  public static ImmutableList<ImmutableSortedSet<Flavor>> generateThinFlavors(
      Set<Flavor> platformFlavors,
      SortedSet<Flavor> flavors) {
    Set<Flavor> platformFreeFlavors =
        Sets.difference(flavors, platformFlavors);
    ImmutableList.Builder<ImmutableSortedSet<Flavor>> thinTargetsBuilder = ImmutableList.builder();
    for (Flavor flavor : flavors) {
      if (platformFlavors.contains(flavor)) {
        thinTargetsBuilder.add(
            ImmutableSortedSet.<Flavor>naturalOrder()
                .addAll(platformFreeFlavors)
                .add(flavor)
                .build());
      }
    }
    return thinTargetsBuilder.build();
  }

  private static final ImmutableSet<Flavor> FORBIDDEN_BUILD_ACTIONS = ImmutableSet.of(
      CxxInferEnhancer.INFER,
      CxxInferEnhancer.INFER_ANALYZE,
      CxxCompilationDatabase.COMPILATION_DATABASE);
}
