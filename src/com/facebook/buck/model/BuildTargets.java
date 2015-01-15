/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.model;

import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Static helpers for working with build targets.
 */
public class BuildTargets {

  /** Utility class: do not instantiate. */
  private BuildTargets() {}

  /**
   * Return a path to a file in the buck-out/bin/ directory. {@code format} will be prepended with
   * the {@link com.facebook.buck.util.BuckConstant#BIN_DIR} and the target base path, then
   * formatted with the target short name.
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @param format {@link String#format} string for the path name.  It should contain one "%s",
   *     which will be filled in with the rule's short name.  It should not start with a slash.
   * @return A {@link java.nio.file.Path} under buck-out/bin, scoped to the base path of
   * {@code target}.
   */

  public static Path getBinPath(BuildTarget target, String format) {
    return Paths.get(String.format("%s/%s" + format,
        BuckConstant.BIN_DIR,
        target.getBasePathWithSlash(),
        target.getShortNameAndFlavorPostfix()));
  }

  /**
   * Return a path to a file in the buck-out/gen/ directory. {@code format} will be prepended with
   * the {@link com.facebook.buck.util.BuckConstant#GEN_DIR} and the target base path, then
   * formatted with the target short name.
   *
   * @param target The {@link BuildTarget} to scope this path to.
   * @param format {@link String#format} string for the path name.  It should contain one "%s",
   *     which will be filled in with the rule's short name.  It should not start with a slash.
   * @return A {@link java.nio.file.Path} under buck-out/gen, scoped to the base path of
   * {@code target}.
   */
  public static Path getGenPath(BuildTarget target, String format) {
    return Paths.get(String.format("%s/%s" + format,
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash(),
        target.getShortNameAndFlavorPostfix()));
  }

  /**
   * Takes the {@link BuildTarget} for {@code hasBuildTarget} and derives a new {@link BuildTarget}
   * from it with the specified flavor.
   * @throws IllegalArgumentException if the original {@link BuildTarget} already has a flavor.
   */
  public static BuildTarget createFlavoredBuildTarget(
      HasBuildTarget hasBuildTarget,
      Flavor flavor) {
    BuildTarget buildTarget = hasBuildTarget.getBuildTarget();
    Preconditions.checkArgument(!buildTarget.isFlavored(),
        "Cannot add flavor %s to %s.",
        flavor,
        buildTarget);
    return BuildTarget.builder(buildTarget)
        .setFlavor(flavor)
        .build();
  }

  /**
   * Returns whether the {@link BuildTarget} `target` is visible to the {@link BuildTarget} `other`
   * using the given visibility patterns.
   */
  public static boolean isVisibleTo(
      BuildTarget target,
      ImmutableSet<BuildTargetPattern> visibilityPatterns,
      BuildTarget other) {

    // Targets in the same build file are always visible to each other.
    if (target.getBaseName().equals(other.getBaseName())) {
      return true;
    }

    for (BuildTargetPattern pattern : visibilityPatterns) {
      if (pattern.apply(other)) {
        return true;
      }
    }

    return false;
  }

  /**
   * @return a new flavored {@link BuildTarget} by merging any existing flavors with the
   *         given flavor.
   */
  public static BuildTarget extendFlavoredBuildTarget(BuildTarget target, Flavor... flavors) {
    BuildTarget.Builder builder = BuildTarget.builder(target);
    for (Flavor flavor : flavors) {
      builder.addFlavor(flavor);
    }
    return builder.build();
  }

  /**
   * @return a new flavored {@link BuildTarget} by merging any existing flavors with the
   *         given flavors.
   */
  public static BuildTarget extendFlavoredBuildTarget(
      BuildTarget target,
      Iterable<Flavor> flavors) {
    return BuildTarget.builder(target).addFlavors(flavors).build();
  }

  /**
   * Propagate flavors represented by the given {@link FlavorDomain} objects from a parent
   * target to it's dependencies.
   */
  public static ImmutableSortedSet<BuildTarget> propagateFlavorDomains(
      BuildTarget target,
      Iterable<FlavorDomain<?>> domains,
      Iterable<BuildTarget> deps) {

    Set<Flavor> flavors = Sets.newHashSet();

    // For each flavor domain, extract the corresponding flavor from the parent target and
    // verify that each dependency hasn't already set this flavor.
    for (FlavorDomain<?> domain : domains) {

      // Now extract all relevant domain flavors from our parent target.
      Optional<Flavor> flavor;
      try {
        flavor = domain.getFlavor(target.getFlavors());
      } catch (FlavorDomainException e) {
        throw new HumanReadableException("%s: %s", target, e.getMessage());
      }
      if (!flavor.isPresent()) {
        throw new HumanReadableException(
            "%s: no flavor for \"%s\"",
            target,
            domain.getName());
      }
      flavors.add(flavor.get());

      // First verify that our deps are not already flavored for our given domains.
      for (BuildTarget dep : deps) {
        Optional<Flavor> depFlavor;
        try {
          depFlavor = domain.getFlavor(dep.getFlavors());
        } catch (FlavorDomainException e) {
          throw new HumanReadableException("%s: dep %s: %s", target, dep, e.getMessage());
        }
        if (depFlavor.isPresent()) {
          throw new HumanReadableException(
              "%s: dep %s already has flavor for \"%s\" : %s",
              target,
              dep,
              domain.getName(),
              flavor.get());
        }
      }
    }

    ImmutableSortedSet.Builder<BuildTarget> flavoredDeps = ImmutableSortedSet.naturalOrder();

    // Now flavor each dependency with the relevant flavors.
    for (BuildTarget dep : deps) {
      flavoredDeps.add(BuildTargets.extendFlavoredBuildTarget(dep, flavors));
    }

    return flavoredDeps.build();
  }

}
