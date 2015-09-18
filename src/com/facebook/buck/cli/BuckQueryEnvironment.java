/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;


import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodes;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The environment of a Buck query that can evaluate queries to produce a result.
 *
 * The query language is documented at docs/command/query.soy
 */
public class BuckQueryEnvironment implements QueryEnvironment<QueryTarget> {
  private final CommandRunnerParams params;
  private final ParserConfig parserConfig;
  private final BuildFileTree buildFileTree;
  private TargetGraph graph = TargetGraph.EMPTY;

  @VisibleForTesting
  protected TargetPatternEvaluator targetPatternEvaluator;

  private Map<BuildTarget, QueryTarget> buildTargetToQueryTarget = new HashMap<>();

  private boolean enableProfiling;

  public BuckQueryEnvironment(
      CommandRunnerParams params,
      boolean enableProfiling) {
    this.params = params;
    this.enableProfiling = enableProfiling;
    this.parserConfig = new ParserConfig(params.getBuckConfig());
    this.buildFileTree = new FilesystemBackedBuildFileTree(
        params.getRepository().getFilesystem(),
        parserConfig.getBuildFileName());
    this.targetPatternEvaluator = new TargetPatternEvaluator(params, enableProfiling);
  }

  public CommandRunnerParams getParams() {
    return params;
  }

  public ParserConfig getParserConfig() {
    return parserConfig;
  }

  public TargetGraph getTargetGraph() {
    return graph;
  }

  /**
   * Evaluate the specified query expression in this environment.
   *
   * @return the resulting set of targets.
   * @throws QueryException if the evaluation failed.
   */
  public Set<QueryTarget> evaluateQuery(QueryExpression expr)
      throws QueryException, InterruptedException {
    Set<String> targetLiterals = new HashSet<>();
    expr.collectTargetPatterns(targetLiterals);
    try {
      targetPatternEvaluator.preloadTargetPatterns(targetLiterals);
    } catch (BuildTargetException | BuildFileParseException | IOException e) {
      throw new QueryException("Error in preloading targets. %s", e.getMessage());
    }
    return expr.eval(this);
  }

  public Set<QueryTarget> evaluateQuery(String query)
      throws QueryException, InterruptedException {
    return evaluateQuery(QueryExpression.parse(query, this));
  }

  @Override
  public ImmutableSet<QueryTarget> getTargetsMatchingPattern(String pattern)
      throws QueryException, InterruptedException {
    try {
      return targetPatternEvaluator.resolveTargetPattern(pattern);
    } catch (BuildTargetException | BuildFileParseException | IOException e) {
      throw new QueryException("Error in resolving targets matching %s", pattern);
    }
  }

  TargetNode<?> getNode(QueryTarget target) throws QueryException, InterruptedException {
    Preconditions.checkState(target instanceof QueryBuildTarget);
    try {
      return params.getParser().getOrLoadTargetNode(
          ((QueryBuildTarget) target).getBuildTarget(),
          params.getBuckEventBus(),
          params.getConsole(),
          params.getEnvironment(),
          enableProfiling);
    } catch (BuildTargetException | BuildFileParseException | IOException e) {
      throw new QueryException("Error getting target node for %s\n%s", target, e.getMessage());
    }
  }

  private QueryTarget getOrCreateQueryBuildTarget(BuildTarget buildTarget) {
    if (buildTargetToQueryTarget.containsKey(buildTarget)) {
      return buildTargetToQueryTarget.get(buildTarget);
    }
    QueryBuildTarget queryBuildTarget = QueryBuildTarget.of(buildTarget);
    buildTargetToQueryTarget.put(buildTarget, queryBuildTarget);
    return queryBuildTarget;
  }

  public ImmutableSet<QueryTarget> getTargetsFromBuildTargetsContainer(
      Iterable<? extends  HasBuildTarget> buildTargetsContainer) {
    ImmutableSortedSet.Builder<QueryTarget> builder = ImmutableSortedSet.naturalOrder();
    for (HasBuildTarget hasBuildTarget : buildTargetsContainer) {
      builder.add(getOrCreateQueryBuildTarget(hasBuildTarget.getBuildTarget()));
    }
    return builder.build();
  }

  public ImmutableSet<TargetNode<?>> getNodesFromQueryTargets(Iterable<QueryTarget> input)
      throws QueryException, InterruptedException {
    ImmutableSet.Builder<TargetNode<?>> builder = ImmutableSet.builder();
    for (QueryTarget target : input) {
      builder.add(getNode(target));
    }
    return builder.build();
  }

  /** Given a set of target nodes, returns the build targets. */
  private static Set<BuildTarget> getTargetsFromNodes(Iterable<TargetNode<?>> input) {
    Set<BuildTarget> result = new LinkedHashSet<>();
    for (TargetNode<?> node : input) {
      result.add(node.getBuildTarget());
    }
    return result;
  }

  @Override
  public Collection<QueryTarget> getFwdDeps(Iterable<QueryTarget> targets)
      throws QueryException, InterruptedException {
    Set<QueryTarget> result = new LinkedHashSet<>();
    for (QueryTarget target : targets) {
      TargetNode<?> node = getNode(target);
      result.addAll(getTargetsFromBuildTargetsContainer(graph.getOutgoingNodesFor(node)));
    }
    return result;
  }

  @Override
  public Collection<QueryTarget> getReverseDeps(Iterable<QueryTarget> targets)
      throws QueryException, InterruptedException {
    Set<QueryTarget> result = new LinkedHashSet<>();
    for (QueryTarget target : targets) {
      TargetNode<?> node = getNode(target);
      result.addAll(getTargetsFromBuildTargetsContainer(graph.getIncomingNodesFor(node)));
    }
    return result;
  }

  @Override
  public ImmutableSet<QueryTarget> getTransitiveClosure(Set<QueryTarget> targets)
      throws QueryException, InterruptedException {
    Set<TargetNode<?>> nodes = new LinkedHashSet<>();
    for (QueryTarget target : targets) {
      nodes.add(getNode(target));
    }
    // Reusing the existing getSubgraph() for simplicity. It builds the graph when we only need the
    // nodes. The impact of creating the edges in terms of time and space should be minimal.
    return getTargetsFromBuildTargetsContainer(graph.getSubgraph(nodes).getNodes());
  }

  private void buildGraphForBuildTargets(Set<BuildTarget> targets)
      throws QueryException, InterruptedException {
    try {
      graph = params.getParser().buildTargetGraphForBuildTargets(
          targets,
          parserConfig,
          params.getBuckEventBus(),
          params.getConsole(),
          params.getEnvironment(),
          enableProfiling);
    } catch (BuildTargetException | BuildFileParseException | IOException e) {
      throw new QueryException("Error in building depencency graph");
    }
  }

  @Override
  public void buildTransitiveClosure(Set<QueryTarget> targets, int maxDepth)
      throws QueryException, InterruptedException {
    // Filter QueryTargets that are build targets and not yet present in the build target graph.
    Set<BuildTarget> graphTargets = getTargetsFromNodes(graph.getNodes());
    Set<BuildTarget> newBuildTargets = new HashSet<>();
    for (QueryTarget target : targets) {
      if (target instanceof QueryBuildTarget) {
        BuildTarget buildTarget = ((QueryBuildTarget) target).getBuildTarget();
        if (!graphTargets.contains(buildTarget)) {
          newBuildTargets.add(buildTarget);
        }
      }
    }
    if (!newBuildTargets.isEmpty()) {
      buildGraphForBuildTargets(Sets.union(newBuildTargets, graphTargets));
      for (BuildTarget buildTarget : getTargetsFromNodes(graph.getNodes())) {
        if (!buildTargetToQueryTarget.containsKey(buildTarget)) {
          buildTargetToQueryTarget.put(buildTarget, QueryBuildTarget.of(buildTarget));
        }
      }
    }
  }

  @Override
  public ImmutableSet<QueryTarget> getTestsForTarget(QueryTarget target)
      throws QueryException, InterruptedException {
    return getTargetsFromBuildTargetsContainer(TargetNodes.getTestTargetsForNode(getNode(target)));
  }

  @Override
  public ImmutableSet<QueryTarget> getFileOwners(ImmutableList<String> files)
      throws InterruptedException, QueryException {
    try {
      AuditOwnerCommand.OwnersReport report = AuditOwnerCommand.buildOwnersReport(
          params,
          parserConfig,
          buildFileTree,
          files,
          /* guessForDeletedEnabled */ false);
      return getTargetsFromBuildTargetsContainer(report.owners.keySet());
    } catch (BuildFileParseException | BuildTargetException | IOException e) {
      throw new QueryException("Could not parse build targets.\n%s", e.getMessage());
    }
  }

  @Override
  public String getTargetKind(QueryTarget target) throws QueryException, InterruptedException {
    return getNode(target).getType().getName();
  }

  @Override
  public ImmutableSet<QueryTarget> getTargetsInAttribute(QueryTarget target, String attribute)
      throws QueryException, InterruptedException {
    return QueryTargetAccessor.getTargetsInAttribute(getNode(target), attribute);
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      QueryTarget target,
      String attribute,
      final Predicate<Object> predicate)
      throws QueryException, InterruptedException {
    return QueryTargetAccessor.filterAttributeContents(getNode(target), attribute, predicate);
  }

  @Override
  public Iterable<QueryFunction> getFunctions() {
    return DEFAULT_QUERY_FUNCTIONS;
  }

}
