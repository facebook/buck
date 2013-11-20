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
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.List;

/**
 * A subgraph of the full dependency graph, which is also a valid dependency graph.
 */
public class PartialGraph {

  private final DependencyGraph graph;
  private final List<BuildTarget> targets;

  @VisibleForTesting
  PartialGraph(DependencyGraph graph, List<BuildTarget> targets) {
    this.graph = graph;
    this.targets = ImmutableList.copyOf(targets);
  }

  public DependencyGraph getDependencyGraph() {
    return graph;
  }

  public List<BuildTarget> getTargets() {
    return targets;
  }

  public static PartialGraph createFullGraph(
      ProjectFilesystem projectFilesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus) throws BuildTargetException, BuildFileParseException, IOException {
    return createPartialGraph(RawRulePredicates.alwaysTrue(),
        projectFilesystem,
        includes,
        parser,
        eventBus);
  }

  public static PartialGraph createPartialGraph(
      RawRulePredicate predicate,
      ProjectFilesystem filesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus) throws BuildTargetException, BuildFileParseException, IOException {
    Preconditions.checkNotNull(predicate);

    List<BuildTarget> targets = parser.filterAllTargetsInProject(filesystem, includes, predicate);

    return parseAndCreateGraphFromTargets(targets, includes, parser, eventBus);
  }

  /**
   * Creates a partial graph of all {@link com.facebook.buck.rules.BuildRule}s that are transitive
   * dependencies of (rules that pass {@code predicate} and are contained in BUCK files that contain
   * transitive dependencies of the {@link BuildTarget}s defined in {@code roots}).
   */
  public static PartialGraph createPartialGraphFromRoots(
      Iterable<BuildTarget> roots,
      RawRulePredicate predicate,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus) throws BuildTargetException, BuildFileParseException, IOException {
    Preconditions.checkNotNull(predicate);

    List<BuildTarget> targets = parser.filterTargetsInProjectFromRoots(
        roots, includes, eventBus, predicate);

    return parseAndCreateGraphFromTargets(targets, includes, parser, eventBus);
  }

  private static PartialGraph parseAndCreateGraphFromTargets(
      Iterable<BuildTarget> targets,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus) throws BuildTargetException, BuildFileParseException, IOException {

    Preconditions.checkNotNull(parser);

    // Now that the Parser is loaded up with the set of all build rules, use it to create a
    // DependencyGraph of only the targets we want to build.
    DependencyGraph graph = parser.parseBuildFilesForTargets(targets, includes, eventBus);

    return new PartialGraph(graph, ImmutableList.copyOf(targets));
  }
}
