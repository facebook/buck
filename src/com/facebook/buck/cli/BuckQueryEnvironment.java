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

import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * The environment of a Buck query that can evaluate queries to produce a result.
 *
 * The query language is documented at docs/command/query.soy
 */
public class BuckQueryEnvironment implements QueryEnvironment<QueryTarget> {
  private final Map<String, Set<QueryTarget>> letBindings = new HashMap<>();
  private final CommandRunnerParams params;
  private final ParserConfig parserConfig;
  private final BuildFileTree buildFileTree;
  private TargetGraph graph = TargetGraph.EMPTY;

  @VisibleForTesting
  protected TargetPatternEvaluator targetPatternEvaluator;

  private Map<BuildTarget, QueryTarget> buildTargetToQueryTarget = new HashMap<>();

  private final Set<Setting> settings;
  private boolean enableProfiling;

  public BuckQueryEnvironment(
      CommandRunnerParams params,
      Set<Setting> settings,
      boolean enableProfiling) {
    this.params = params;
    this.settings = settings;
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

  public BuildFileTree getBuildFileTree() {
    return buildFileTree;
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
      throw new QueryException("Error in preloading targets. " + e.getMessage());
    }
    return expr.eval(this);
  }

  public Set<QueryTarget> evaluateQuery(String query)
      throws QueryException, InterruptedException {
    return evaluateQuery(QueryExpression.parse(query, this));
  }

  @Override
  public void reportBuildFileError(QueryExpression caller, String message) throws QueryException {
    throw new QueryException("Not implemented yet.");
  }

  @Override
  public Set<QueryTarget> getBuildFiles(QueryExpression caller, Set<QueryTarget> nodes)
      throws QueryException {
    throw new QueryException("Not implemented yet.");
  }

  @Override
  public TargetAccessor<QueryTarget> getAccessor() {
    throw new RuntimeException("Not implemented yet.");
  }

  @Override
  public ImmutableSet<QueryTarget> getTargetsMatchingPattern(QueryExpression owner, String pattern)
      throws QueryException {
    try {
      return targetPatternEvaluator.resolveTargetPattern(pattern);
    } catch (BuildTargetException | BuildFileParseException | IOException e) {
      throw new QueryException("Error in resolving targets matching " + pattern);
    } catch (InterruptedException e) {
      throw (QueryException) new QueryException("Interrupted").initCause(e);
    }
  }

  @Override
  public QueryTarget getOrCreate(QueryTarget target) {
    // This function is inherited from QueryEnvironment. Currently, it is not used
    // anywhere. It will be used if we support LabelsFunction or TestsFunction from Bazel.
    // When the target nodes graph is built incrementally, support creation of nodes here.
    return target;
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
      throw new QueryException("Error getting target node for " + target + "\n" + e.getMessage());
    }
  }

  private QueryTarget getOrCreateQueryBuildTarget(BuildTarget buildTarget) {
    if (buildTargetToQueryTarget.containsKey(buildTarget)) {
      return buildTargetToQueryTarget.get(buildTarget);
    }
    AbstractQueryBuildTarget queryBuildTarget = QueryBuildTarget.of(buildTarget);
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
      Preconditions.checkState(target instanceof QueryBuildTarget);
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
  public Collection<QueryTarget> getFwdDeps(Iterable<QueryTarget> targets) {
    Set<QueryTarget> result = new LinkedHashSet<>();
    try {
      for (QueryTarget target : targets) {
        Preconditions.checkState(target instanceof AbstractQueryBuildTarget);
        TargetNode<?> node = getNode(target);
        result.addAll(getTargetsFromBuildTargetsContainer(graph.getOutgoingNodesFor(node)));
      }
    } catch (QueryException | InterruptedException e) {
      // Can't add exceptions to inherited method signature from Bazel.
      throw new RuntimeException(e);
    }
    return result;
  }

  @Override
  public Collection<QueryTarget> getReverseDeps(Iterable<QueryTarget> targets) {
    Set<QueryTarget> result = new LinkedHashSet<>();
    try {
      for (QueryTarget target : targets) {
        TargetNode<?> node = getNode(target);
        result.addAll(getTargetsFromBuildTargetsContainer(graph.getIncomingNodesFor(node)));
      }
    } catch (QueryException | InterruptedException e) {
      // Can't add exceptions to inherited method signature from Bazel.
      throw new RuntimeException(e);
    }
    return result;
  }

  @Override
  public ImmutableSet<QueryTarget> getTransitiveClosure(Set<QueryTarget> targets) {
    Set<TargetNode<?>> nodes = new LinkedHashSet<>();
    try {
      for (QueryTarget target : targets) {
        nodes.add(getNode(target));
      }
    } catch (QueryException | InterruptedException e) {
      // Can't add exceptions to inherited method signature from Bazel.
      throw new RuntimeException(e);
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
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      throw new QueryException("error in building depencency graph");
    }
  }

  private void updateTargetGraph(Set<QueryTarget> targets)
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
  public void buildTransitiveClosure(
      QueryExpression caller,
      Set<QueryTarget> targets,
      int maxDepth) throws QueryException, InterruptedException {
    updateTargetGraph(targets);
  }

  @Override
  public Set<QueryTarget> getNodesOnPath(QueryTarget from, QueryTarget to) {
    throw new RuntimeException("Not implemented yet.");
  }

  @Nullable
  @Override
  public Set<QueryTarget> getVariable(String name) {
    return letBindings.get(name);
  }

  @Nullable
  @Override
  public Set<QueryTarget> setVariable(String name, Set<QueryTarget> value) {
    return letBindings.put(name, value);
  }

  @Override
  public boolean isSettingEnabled(Setting setting) {
    return settings.contains(Preconditions.checkNotNull(setting));
  }

  @Override
  public Iterable<QueryFunction> getFunctions() {
    return ImmutableList.of(
        new QueryAllPathsFunction(),
        new QueryAttrFilterFunction(),
        new QueryDepsFunction(),
        new QueryFilterFunction(),
        new QueryKindFunction(),
        new QueryLabelsFunction(),
        new QueryOwnerFunction(),
        new QueryRdepsFunction(),
        new QueryTestsOfFunction()
    );
  }

  public String getTargetKind(QueryTarget target) throws QueryException, InterruptedException {
    return getNode(target).getType().getName();
  }

  public ImmutableSet<QueryTarget> getTargetsInAttribute(QueryTarget target, String attribute)
      throws QueryException, InterruptedException {
    Preconditions.checkState(target instanceof QueryBuildTarget);
    return QueryTargetAccessor.getTargetsInAttribute(getNode(target), attribute);
  }

  public ImmutableSet<Object> filterAttributeContents(
      QueryTarget target,
      String attribute,
      final Predicate<Object> predicate)
      throws QueryException, InterruptedException {
    Preconditions.checkState(target instanceof QueryBuildTarget);
    return QueryTargetAccessor.filterAttributeContents(
        Preconditions.checkNotNull(getNode(target)), attribute, predicate);
  }

}
