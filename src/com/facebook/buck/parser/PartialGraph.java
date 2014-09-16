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
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;

/**
 * A subgraph of the full action graph, which is also a valid action graph.
 */
public class PartialGraph {

  private final ActionGraph graph;
  private final ImmutableSet<BuildTarget> targets;

  @VisibleForTesting
  PartialGraph(ActionGraph graph, ImmutableSet<BuildTarget> targets) {
    this.graph = Preconditions.checkNotNull(graph);
    this.targets = Preconditions.checkNotNull(targets);
  }

  public ActionGraph getActionGraph() {
    return graph;
  }

  public ImmutableSet<BuildTarget> getTargets() {
    return targets;
  }

  public static PartialGraph createFullGraph(
      ProjectFilesystem projectFilesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment,
      boolean enableProfiling)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    return createPartialGraph(
        RuleJsonPredicates.alwaysTrue(),
        projectFilesystem,
        includes,
        parser,
        eventBus,
        console,
        environment,
        enableProfiling);
  }

  public static PartialGraph createPartialGraph(
      RuleJsonPredicate predicate,
      ProjectFilesystem filesystem,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console,
      ImmutableMap<String, String> environment,
      boolean enableProfiling)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets = parser.filterAllTargetsInProject(
        filesystem,
        includes,
        predicate,
        console,
        environment,
        eventBus,
        enableProfiling);

    return createPartialGraph(
        targets,
        includes,
        parser,
        eventBus,
        console,
        environment);
  }

  public static PartialGraph createPartialGraph(
      ImmutableSet<BuildTarget> targets,
      Iterable<String> includes,
      Parser parser,
      BuckEventBus eventBus,
      Console console, ImmutableMap<String, String> environment)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    Preconditions.checkNotNull(parser);

    // Now that the Parser is loaded up with the set of all build rules, use it to create a
    // DependencyGraph of only the targets we want to build.
    ActionGraph graph = parser.buildTargetGraph(
        targets,
        includes,
        eventBus,
        console,
        environment).buildActionGraph();

    return new PartialGraph(graph, targets);
  }
}
