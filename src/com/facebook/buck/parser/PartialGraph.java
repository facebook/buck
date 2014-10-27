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
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;

/**
 * A subgraph of the full target graph, which is also a valid target graph.
 */
public class PartialGraph {

  private final TargetGraph graph;
  private final ImmutableSet<BuildTarget> targets;

  @VisibleForTesting
  PartialGraph(TargetGraph graph, ImmutableSet<BuildTarget> targets) {
    this.graph = Preconditions.checkNotNull(graph);
    this.targets = Preconditions.checkNotNull(targets);
  }

  public TargetGraph getTargetGraph() {
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

    TargetGraph graph = parser.buildTargetGraphForTargetNodeSpecs(
        ImmutableList.of(
            new TargetNodePredicateSpec(
                Predicates.<TargetNode<?>>alwaysTrue(),
                projectFilesystem.getIgnorePaths())),
        includes,
        eventBus,
        console,
        environment,
        enableProfiling);

    return new PartialGraph(
        graph,
        FluentIterable.from(graph.getNodes()).transform(HasBuildTarget.TO_TARGET).toSet());
  }

  public static PartialGraph createPartialGraph(
      Predicate<TargetNode<?>> predicate,
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
      Console console,
      ImmutableMap<String, String> environment)
      throws BuildTargetException, BuildFileParseException, IOException, InterruptedException {
    Preconditions.checkNotNull(parser);

    TargetGraph graph = parser.buildTargetGraphForBuildTargets(
        targets,
        includes,
        eventBus,
        console,
        environment,
        /* enableProfiling */ false);

    return new PartialGraph(graph, targets);
  }
}
