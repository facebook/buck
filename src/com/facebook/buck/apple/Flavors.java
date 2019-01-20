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

package com.facebook.buck.apple;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

public class Flavors {

  private Flavors() {}

  public static Predicate<BuildTarget> containsFlavors(FlavorDomain<?> domain) {
    return input -> {
      ImmutableSet<Flavor> flavorSet =
          Sets.intersection(domain.getFlavors(), input.getFlavors()).immutableCopy();
      return !flavorSet.isEmpty();
    };
  }

  public static Predicate<BuildTarget> containsFlavor(Flavor flavor) {
    return input -> input.getFlavors().contains(flavor);
  }

  /**
   * Propagate flavors represented by the given {@link FlavorDomain} objects from a parent target to
   * its dependencies.
   */
  public static ImmutableSortedSet<BuildTarget> propagateFlavorDomains(
      BuildTarget target, Iterable<FlavorDomain<?>> domains, Iterable<BuildTarget> deps) {

    Set<Flavor> flavors = new HashSet<>();

    // For each flavor domain, extract the corresponding flavor from the parent target and
    // verify that each dependency hasn't already set this flavor.
    for (FlavorDomain<?> domain : domains) {

      // Now extract all relevant domain flavors from our parent target.
      ImmutableSet<Flavor> flavorSet =
          Sets.intersection(domain.getFlavors(), target.getFlavors()).immutableCopy();

      if (flavorSet.isEmpty()) {
        throw new HumanReadableException("%s: no flavor for \"%s\"", target, domain.getName());
      }
      flavors.addAll(flavorSet);

      // First verify that our deps are not already flavored for our given domains.
      for (BuildTarget dep : deps) {
        if (domain.getFlavor(dep).isPresent()) {
          throw new HumanReadableException(
              "%s: dep %s already has flavor for \"%s\" : %s",
              target, dep, domain.getName(), flavorSet.toString());
        }
      }
    }

    ImmutableSortedSet.Builder<BuildTarget> flavoredDeps = ImmutableSortedSet.naturalOrder();

    // Now flavor each dependency with the relevant flavors.
    for (BuildTarget dep : deps) {
      flavoredDeps.add(dep.withAppendedFlavors(flavors));
    }

    return flavoredDeps.build();
  }

  /**
   * Propagate a build target's flavors in a certain domain to a list of other build targets.
   *
   * @param domain the flavor domain to be propagated.
   * @param buildTarget the build target containing the flavors to be propagated
   * @param deps list of BuildTargetPaths to propagate the flavors to. If a target already contains
   *     one or more flavors in domain, it is left unchanged.
   * @return the list of BuildTargetPaths with any flavors propagated.
   */
  public static FluentIterable<BuildTarget> propagateFlavorsInDomainIfNotPresent(
      FlavorDomain<?> domain, BuildTarget buildTarget, FluentIterable<BuildTarget> deps) {
    if (domain.containsAnyOf(buildTarget.getFlavors())) {
      FluentIterable<BuildTarget> targetsWithFlavorsAlready =
          deps.filter(containsFlavors(domain)::test);

      FluentIterable<BuildTarget> targetsWithoutFlavors =
          deps.filter(containsFlavors(domain).negate()::test);

      deps =
          targetsWithFlavorsAlready.append(
              propagateFlavorDomains(buildTarget, ImmutableSet.of(domain), targetsWithoutFlavors));
    }

    return deps;
  }
}
