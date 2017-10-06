/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.distributed;

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Stream;

public class BuildTargetsQueue {
  private static final Logger LOG = Logger.get(BuildTargetsQueue.class);

  private List<EnqueuedTarget> zeroDependencyTargets;
  private final Map<String, EnqueuedTarget> allEnqueuedTargets;

  private BuildTargetsQueue(
      List<EnqueuedTarget> zeroDependencyTargets, Map<String, EnqueuedTarget> allEnqueuedTargets) {
    this.zeroDependencyTargets = zeroDependencyTargets;
    this.allEnqueuedTargets = allEnqueuedTargets;
  }

  public static BuildTargetsQueue newEmptyQueue() {
    return new BuildTargetsQueue(new ArrayList<>(), new HashMap<>());
  }

  public static BuildTargetsQueue newQueue(
      BuildRuleResolver resolver, Iterable<BuildTarget> targetsToBuild) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    // Build the reverse dependency graph by traversing the action graph Top-Down.
    Map<String, Set<String>> allReverseDeps = new HashMap<>();
    Map<String, Set<String>> allForwardDeps = new HashMap<>();
    Set<String> visitedTargets = new HashSet<>();
    Queue<BuildRule> buildRulesToProcess =
        Lists.newLinkedList(
            FluentIterable.from(targetsToBuild)
                .transform(
                    x -> {
                      BuildRule rule = resolver.getRule(x);
                      visitedTargets.add(ruleToTarget(rule));
                      return rule;
                    }));
    while (!buildRulesToProcess.isEmpty()) {
      BuildRule rule = buildRulesToProcess.remove();

      String target = ruleToTarget(rule);
      allForwardDeps.put(target, new HashSet<>());

      ImmutableSortedSet.Builder<BuildRule> allDependencies = ImmutableSortedSet.naturalOrder();

      // Get all standard build dependencies
      allDependencies.addAll(rule.getBuildDeps());

      // Optionally add in any run-time deps
      if (rule instanceof HasRuntimeDeps) {
        LOG.debug(String.format("[%s] has runtime deps", rule.getFullyQualifiedName()));

        Stream<BuildTarget> runtimeDepPaths = ((HasRuntimeDeps) rule).getRuntimeDeps(ruleFinder);
        ImmutableSet<BuildRule> runtimeDeps =
            resolver.getAllRules(runtimeDepPaths.collect(MoreCollectors.toImmutableSet()));

        allDependencies.addAll(runtimeDeps);
      }

      for (BuildRule dependencyRule : allDependencies.build()) {
        String dependencyTarget = ruleToTarget(dependencyRule);

        if (!allReverseDeps.containsKey(dependencyTarget)) {
          allReverseDeps.put(dependencyTarget, new HashSet<>());
        }
        allReverseDeps.get(dependencyTarget).add(target);
        allForwardDeps.get(target).add(dependencyTarget);

        if (!visitedTargets.contains(dependencyTarget)) {
          visitedTargets.add(dependencyTarget);
          buildRulesToProcess.add(dependencyRule);
        }
      }
    }

    // Do the reference counting and create the EnqueuedTargets.
    List<EnqueuedTarget> zeroDependencyTargets = new ArrayList<>();
    Map<String, EnqueuedTarget> allEnqueuedTargets = new HashMap<>();
    for (String target : visitedTargets) {
      Iterable<String> currentRevDeps = null;
      if (allReverseDeps.containsKey(target)) {
        currentRevDeps = allReverseDeps.get(target);
      } else {
        currentRevDeps = new ArrayList<>();
      }

      EnqueuedTarget enqueuedTarget =
          new EnqueuedTarget(
              target,
              ImmutableList.copyOf(currentRevDeps),
              allForwardDeps.get(target).size(),
              ImmutableSet.copyOf(allForwardDeps.get(target)));
      allEnqueuedTargets.put(target, enqueuedTarget);

      if (enqueuedTarget.areAllDependenciesResolved()) {
        zeroDependencyTargets.add(enqueuedTarget);
      }
    }

    return new BuildTargetsQueue(zeroDependencyTargets, allEnqueuedTargets);
  }

  public ImmutableList<String> dequeueZeroDependencyNodes(ImmutableList<String> finishedNodes) {
    // Decrement reference counting.
    for (String node : finishedNodes) {
      EnqueuedTarget target = Preconditions.checkNotNull(allEnqueuedTargets.get(node));
      for (String dependent : target.getDependentTargets()) {
        EnqueuedTarget dep = Preconditions.checkNotNull(allEnqueuedTargets.get(dependent));
        dep.decrementUnsatisfiedDeps(node);
        if (dep.areAllDependenciesResolved()) {
          zeroDependencyTargets.add(dep);
        }
      }
    }

    // Return all the Targets that have all dependencies resolved.
    ImmutableList<String> targetsReadyToBuild =
        ImmutableList.copyOf(
            FluentIterable.from(zeroDependencyTargets).transform(x -> x.getBuildTarget()));
    zeroDependencyTargets.clear();
    return targetsReadyToBuild;
  }

  private static String ruleToTarget(BuildRule rule) {
    return rule.getFullyQualifiedName();
  }

  private static class EnqueuedTarget {
    private final String buildTarget;
    private final ImmutableList<String> dependentTargets;
    private final Set<String> allDependencies;
    private final Set<String> dependenciesRemaining;
    private int unsatisfiedDependencies;

    private EnqueuedTarget(
        String buildTarget,
        ImmutableList<String> dependentTargets,
        int numberOfDependencies,
        ImmutableSet<String> dependenciesRemaining) {
      this.buildTarget = buildTarget;
      this.dependentTargets = dependentTargets;
      this.unsatisfiedDependencies = numberOfDependencies;
      this.dependenciesRemaining = new HashSet<>(dependenciesRemaining);
      this.allDependencies = new HashSet<>(dependenciesRemaining);
    }

    public boolean areAllDependenciesResolved() {
      return 0 == unsatisfiedDependencies;
    }

    public String getBuildTarget() {
      return buildTarget;
    }

    public ImmutableList<String> getDependentTargets() {
      return dependentTargets;
    }

    public void decrementUnsatisfiedDeps(String dependency) {
      if (!dependenciesRemaining.contains(dependency)) {
        boolean isActualDependency = allDependencies.contains(dependency);
        String errorMessage =
            String.format(
                "[%s] is not a remaining dependency of [%s]. Is real dependency [%s]",
                dependency, buildTarget, isActualDependency);
        LOG.error(errorMessage);
        throw new RuntimeException(errorMessage);
      }

      LOG.debug(
          String.format(
              "Removing [%s] from remaining dependencies for [%s]", dependency, buildTarget));
      dependenciesRemaining.remove(dependency);
      --unsatisfiedDependencies;
      Preconditions.checkArgument(
          unsatisfiedDependencies >= 0,
          "The number of unsatisfied dependencies can never be negative.");
    }

    @Override
    public String toString() {
      return "EnqueuedTarget{"
          + "buildTarget='"
          + buildTarget
          + '\''
          + ", unsatisfiedDependencies="
          + unsatisfiedDependencies
          + ", dependentTargets="
          + dependentTargets
          + '}';
    }
  }
}
