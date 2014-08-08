/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.List;

/**
 * A subgraph of the full action graph, which is also a valid action graph.
 */
public class PartialGraph {

  private final ActionGraph graph;
  private final List<BuildTarget> targets;

  @VisibleForTesting
  PartialGraph(ActionGraph graph, List<BuildTarget> targets) {
    this.graph = graph;
    this.targets = ImmutableList.copyOf(targets);
  }

  public ActionGraph getActionGraph() {
    return graph;
  }

  public List<BuildTarget> getTargets() {
    return targets;
  }

  public static PartialGraph createFullGraph(
      ProjectFilesystem projectFilesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    return createPartialGraph(
        RuleJsonPredicates.alwaysTrue(),
        projectFilesystem,
        includes,
        parser,
        eventBus,
        console,
        environment);
  }

  public static PartialGraph createPartialGraph(
      RuleJsonPredicate predicate,
      ProjectFilesystem filesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    List<BuildTarget> targets = parser.filterAllTargetsInProject(filesystem,
        includes,
        predicate,
        console,
        environment);

    return parseAndCreateGraphFromTargets(
        targets,
        includes,
        parser,
        eventBus,
        console,
        environment);
  }

  /**
   * Creates a graph containing the {@link BuildRule}s identified by {@code roots} and their
   * dependencies. Then for each pair of {@link RuleJsonPredicate} in {@code predicates} and
   * {@link AssociatedRulePredicate} in {@code associatedRulePredicates}, rules throughout the
   * project that pass are added to the graph.
   */
  public static PartialGraph createPartialGraphFromRootsWithAssociatedRules(
      Iterable<BuildTarget> roots,
      ImmutableList<RuleJsonPredicate> predicates,
      ImmutableList<AssociatedRulePredicate> associatedRulePredicates,
      ProjectFilesystem filesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    Iterable<BuildTarget> buildTargets = parser.targetsInProjectFromRoots(
        roots, includes, eventBus, console, environment);

    PartialGraph partialGraph = parseAndCreateGraphFromTargets(
        buildTargets,
        includes,
        parser,
        eventBus,
        console,
        environment);

    for (int i = 0; i < predicates.size(); i++) {
      RuleJsonPredicate predicate = predicates.get(i);
      AssociatedRulePredicate associatedRulePredicate = associatedRulePredicates.get(i);

      PartialGraph associatedPartialGraph = PartialGraph.createPartialGraph(
          predicate,
          filesystem,
          includes,
          parser,
          eventBus,
          console,
          environment);

      ImmutableList.Builder<BuildTarget> associatedRulesBuilder =
          ImmutableList.<BuildTarget>builder().addAll(roots);

      for (BuildTarget buildTarget : associatedPartialGraph.getTargets()) {
        BuildRule buildRule = associatedPartialGraph
            .getActionGraph()
            .findBuildRuleByTarget(buildTarget);
        if (associatedRulePredicate.isMatch(buildRule, partialGraph.getActionGraph())) {
          associatedRulesBuilder.add(buildRule.getBuildTarget());
        }
      }

      Iterable<BuildTarget> allTargets = parser.targetsInProjectFromRoots(
          associatedRulesBuilder.build(), includes, eventBus, console, environment);

      partialGraph = parseAndCreateGraphFromTargets(
          allTargets,
          includes,
          parser,
          eventBus,
          console,
          environment);
    }

    return partialGraph;
  }

  /**
   * Like {@link #createPartialGraphFromRootsWithAssociatedRules}, but trades accuracy for speed.
   *
   * <p>The graph returned from this method will include all transitive deps of the roots, but
   * might also include rules that are not actually dependencies.  This looseness allows us to
   * run faster by avoiding a post-hoc filtering step.
   */
  public static PartialGraph createPartialGraphIncludingRoots(
      Iterable<BuildTarget> roots,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console, ImmutableMap<String, String> environment)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    return parseAndCreateGraphFromTargets(roots, includes, parser, eventBus, console, environment);
  }

  private static PartialGraph parseAndCreateGraphFromTargets(
      Iterable<BuildTarget> targets,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console, ImmutableMap<String, String> environment)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {

    Preconditions.checkNotNull(parser);

    // Now that the Parser is loaded up with the set of all build rules, use it to create a
    // DependencyGraph of only the targets we want to build.
    ActionGraph graph = parser.parseBuildFilesForTargets(
        targets,
        includes,
        eventBus,
        console,
        environment);

    return new PartialGraph(graph, ImmutableList.copyOf(targets));
  }
}
