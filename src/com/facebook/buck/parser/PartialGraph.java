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

import com.facebook.buck.json.BuildFileToJsonParser;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

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
      File projectDirectoryRoot,
      ArtifactCache artifactCache,
      Iterable<String> includes) throws NoSuchBuildTargetException, IOException {
    return createPartialGraph(RawRulePredicates.alwaysTrue(),
        projectDirectoryRoot,
        artifactCache,
        includes);
  }

  public static PartialGraph createPartialGraph(
      RawRulePredicate predicate,
      File projectDirectoryRoot,
      ArtifactCache artifactCache,
      Iterable<String> includes) throws NoSuchBuildTargetException, IOException {
    List<Map<String, Object>> ruleObjects = BuildFileToJsonParser.getAllRulesInProject(
        projectDirectoryRoot, includes);
    ProjectFilesystem filesystem = new ProjectFilesystem(projectDirectoryRoot);
    KnownBuildRuleTypes buildRuleTypes = new KnownBuildRuleTypes();
    BuildFileTree buildFiles = BuildFileTree.constructBuildFileTree(filesystem);
    Parser parser = new Parser(filesystem, buildRuleTypes, artifactCache, buildFiles);
    List<BuildTarget> targets = parser.parseRawRules(ruleObjects, predicate);

    // Now that the Parser is loaded up with the set of all build rules, use it to create a
    // DependencyGraph of only the targets we want to build.
    DependencyGraph graph = parser.parseBuildFilesForTargets(targets, includes);

    return new PartialGraph(graph, targets);
  }
}
