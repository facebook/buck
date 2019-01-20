/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rules.common;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuildRules {

  private BuildRules() {
    // Utility class.
  }

  public static ImmutableSortedSet<BuildRule> toBuildRulesFor(
      BuildTarget invokingBuildTarget,
      BuildRuleResolver ruleResolver,
      Iterable<BuildTarget> buildTargets) {
    ImmutableSortedSet.Builder<BuildRule> buildRules = ImmutableSortedSet.naturalOrder();

    for (BuildTarget target : buildTargets) {
      Optional<BuildRule> buildRule = ruleResolver.getRuleOptional(target);
      if (buildRule.isPresent()) {
        buildRules.add(buildRule.get());
      } else {
        throw new HumanReadableException(
            "No rule for %s found when processing %s",
            target, invokingBuildTarget.getFullyQualifiedName());
      }
    }

    return buildRules.build();
  }

  public static Predicate<BuildRule> isBuildRuleWithTarget(BuildTarget target) {
    return input -> input.getBuildTarget().equals(target);
  }

  /**
   * @return the set of {@code BuildRule}s exported by {@code ExportDependencies} from the given
   *     rules.
   */
  public static ImmutableSortedSet<BuildRule> getExportedRules(
      Iterable<? extends BuildRule> rules) {
    ImmutableSortedSet.Builder<BuildRule> exportedRules = ImmutableSortedSet.naturalOrder();
    buildExportedRules(rules, exportedRules);
    return exportedRules.build();
  }

  public static ImmutableSet<BuildRule> getUnsortedExportedRules(
      Iterable<? extends BuildRule> rules) {
    ImmutableSet.Builder<BuildRule> exportedRules = ImmutableSet.builder();
    buildExportedRules(rules, exportedRules);
    return exportedRules.build();
  }

  private static void buildExportedRules(
      Iterable<? extends BuildRule> rules, ImmutableCollection.Builder<BuildRule> exportedRules) {
    AbstractBreadthFirstTraversal<ExportDependencies> visitor =
        new AbstractBreadthFirstTraversal<ExportDependencies>(
            Iterables.filter(rules, ExportDependencies.class)) {
          @Override
          public Iterable<ExportDependencies> visit(ExportDependencies exporter) {
            Set<BuildRule> exported = exporter.getExportedDeps();
            Set<BuildRule> exportedProvided = exporter.getExportedProvidedDeps();
            exportedRules.addAll(exported);
            exportedRules.addAll(exportedProvided);
            return Stream.concat(exported.stream(), exportedProvided.stream())
                .filter(ExportDependencies.class::isInstance)
                .map(ExportDependencies.class::cast)
                .collect(Collectors.toSet());
          }
        };
    visitor.start();
  }

  public static ImmutableSet<BuildTarget> getTransitiveRuntimeDeps(
      HasRuntimeDeps rule, BuildRuleResolver resolver) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    ImmutableSet.Builder<BuildTarget> runtimeDeps = ImmutableSet.builder();
    AbstractBreadthFirstTraversal<BuildTarget> visitor =
        new AbstractBreadthFirstTraversal<BuildTarget>(
            rule.getRuntimeDeps(ruleFinder).collect(ImmutableSet.toImmutableSet())) {
          @Override
          public Iterable<BuildTarget> visit(BuildTarget runtimeDep) {
            runtimeDeps.add(runtimeDep);
            Optional<BuildRule> rule = resolver.getRuleOptional(runtimeDep);
            if (rule.isPresent() && rule.get() instanceof HasRuntimeDeps) {
              return ((HasRuntimeDeps) rule.get())
                  .getRuntimeDeps(ruleFinder)
                  .collect(ImmutableSet.toImmutableSet());
            }
            return ImmutableSet.of();
          }
        };
    visitor.start();
    return runtimeDeps.build();
  }
}
