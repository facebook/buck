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
import com.facebook.buck.rules.TestRule;
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
    return createPartialGraph(RawRulePredicates.alwaysTrue(),
        projectFilesystem,
        includes,
        parser,
        eventBus,
        console,
        environment);
  }

  public static PartialGraph createPartialGraph(
      RawRulePredicate predicate,
      ProjectFilesystem filesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    Preconditions.checkNotNull(predicate);

    List<BuildTarget> targets = parser.filterAllTargetsInProject(filesystem,
        includes,
        predicate,
        console,
        environment);

    // filterAllTargetsInProject should only return Null when predicate is null.
    // Check this reasoning is true at runtime to placate static checkers.
    // TODO(#4825537): Refactor filterAllTargetsInProject to not accept null: it confuses robots.
    Preconditions.checkNotNull(targets);

    return parseAndCreateGraphFromTargets(
        targets,
        includes,
        parser,
        eventBus,
        console,
        environment);
  }

  /**
   * Creates a partial graph of all {@link BuildRule}s that are either
   * transitive dependencies or tests for (rules that pass {@code predicate} and are contained in
   * BUCK files that contain transitive dependencies of the {@link BuildTarget}s defined in
   * {@code roots}).
   */
  public static PartialGraph createPartialGraphFromRootsWithTests(
      Iterable<BuildTarget> roots,
      RawRulePredicate predicate,
      ProjectFilesystem filesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    Preconditions.checkNotNull(predicate);

    Iterable<BuildTarget> buildTargets = parser.filterTargetsInProjectFromRoots(
        roots, includes, eventBus, RawRulePredicates.alwaysTrue(), console, environment);

    // filterTargetsInProject should only return null when predicate is null.
    // Check this reasoning is true at runtime to placate static checkers.
    // TODO(#4825537): Refactor filterTargetsInProject to not accept null: it confuses robots.
    Preconditions.checkNotNull(buildTargets);

    ActionGraph buildGraph =
        parseAndCreateGraphFromTargets(
            buildTargets,
            includes,
            parser,
            eventBus,
            console,
            environment)
            .getActionGraph();

    // We have to enumerate all test targets, and see which ones refer to a rule in our build graph
    // with it's src_under_test field.
    ImmutableList.Builder<BuildTarget> buildAndTestTargetsBuilder =
        ImmutableList.<BuildTarget>builder()
            .addAll(roots);

    PartialGraph testGraph = PartialGraph.createPartialGraph(
        RawRulePredicates.isTestRule(),
        filesystem,
        includes,
        parser,
        eventBus,
        console,
        environment);

    ActionGraph testActionGraph = testGraph.getActionGraph();

    // Iterate through all possible test targets, looking for ones who's src_under_test intersects
    // with our build graph.
    for (BuildTarget buildTarget : testGraph.getTargets()) {
      TestRule testRule =
          (TestRule) testActionGraph.findBuildRuleByTarget(buildTarget);
      for (BuildRule buildRuleUnderTest : testRule.getSourceUnderTest()) {
        if (buildGraph.findBuildRuleByTarget(buildRuleUnderTest.getBuildTarget()) != null) {
          buildAndTestTargetsBuilder.add(testRule.getBuildTarget());
          break;
        }
      }
    }

    Iterable<BuildTarget> allTargets = parser.filterTargetsInProjectFromRoots(
        buildAndTestTargetsBuilder.build(), includes, eventBus, predicate, console, environment);

    // filterTargetsInProject should only return null when predicate is null.
    // Check this reasoning is true at runtime to placate static checkers.
    // TODO(#4825537): Refactor filterTargetsInProject to not accept null: it confuses robots.
    Preconditions.checkNotNull(allTargets);

    return parseAndCreateGraphFromTargets(
        allTargets,
        includes,
        parser,
        eventBus,
        console,
        environment);
  }

  /**
   * Creates a partial graph of all {@link BuildRule}s that are transitive
   * dependencies of (rules that pass {@code predicate} and are contained in BUCK files that contain
   * transitive dependencies of the {@link BuildTarget}s defined in {@code roots}).

   */
  public static PartialGraph createPartialGraphFromRoots(
      Iterable<BuildTarget> roots,
      RawRulePredicate predicate,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    Preconditions.checkNotNull(predicate);

    Iterable<BuildTarget> targets = parser.filterTargetsInProjectFromRoots(
        roots, includes, eventBus, predicate, console, environment);

    // filterTargetsInProject should only return null when predicate is null.
    // Check this reasoning is true at runtime to placate static checkers.
    // TODO(#4825537): Refactor filterTargetsInProject to not accept null: it confuses robots.
    Preconditions.checkNotNull(targets);

    return parseAndCreateGraphFromTargets(
        targets,
        includes,
        parser,
        eventBus,
        console,
        environment);
  }


  /**
   * Like {@link #createPartialGraphFromRootsWithTests}, but trades accuracy for speed.
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
