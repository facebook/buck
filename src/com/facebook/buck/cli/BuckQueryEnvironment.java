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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * The environment of a Buck query that can evaluate queries to produce a result.
 */
public class BuckQueryEnvironment implements QueryEnvironment<BuildTarget> {
  private final Map<String, Set<BuildTarget>> letBindings = new HashMap<>();
  private final CommandRunnerParams params;
  private TargetGraph graph = TargetGraph.EMPTY;

  private final Set<Setting> settings;
  private boolean enableProfiling;

  public BuckQueryEnvironment(
      CommandRunnerParams params,
      Set<Setting> settings,
      boolean enableProfiling) {
    this.params = params;
    this.settings = settings;
    this.enableProfiling = enableProfiling;
  }

  public CommandRunnerParams getParams() { return params; }

  /**
   * Evaluate the specified query expression in this environment.
   *
   * @return the resulting set of targets.
   * @throws QueryException if the evaluation failed.
   */
  public Set<BuildTarget> evaluateQuery(QueryExpression expr)
      throws QueryException, InterruptedException {
    return expr.eval(this);
  }

  public Set<BuildTarget> evaluateQuery(String query)
      throws QueryException, InterruptedException {
    return evaluateQuery(QueryExpression.parse(query, this));
  }

  @Override
  public void reportBuildFileError(QueryExpression caller, String message) throws QueryException {
    throw new QueryException("Not implemented yet.");
  }

  @Override
  public Set<BuildTarget> getBuildFiles(QueryExpression caller, Set<BuildTarget> nodes)
      throws QueryException {
    throw new QueryException("Not implemented yet.");
  }

  @Override
  public TargetAccessor<BuildTarget> getAccessor() {
    throw new RuntimeException("Not implemented yet.");
  }

  @Override
  public Set<BuildTarget> getTargetsMatchingPattern(QueryExpression owner, String pattern)
      throws QueryException {
    // TODO(user): only supporting single build targets like buck audit commands.
    // In the future, this should also support build target patterns as well.
    Set<BuildTarget> targets = new LinkedHashSet<>();
    targets.add(
        BuildTargetParser.INSTANCE.parse(
            pattern,
            BuildTargetPatternParser.fullyQualified()));
    return targets;
  }

  @Override
  public BuildTarget getOrCreate(BuildTarget target) {
    // TODO(user): this function is inherited from QueryEnvironment. Currently, it is not used
    // anywhere. It will be used if we support LabelsFunction or TestsFunction from Bazel.
    // When the target nodes graph is built incrementally, support creation of nodes here.
    return target;
  }

  @Nullable
  TargetNode<?> getNode(BuildTarget target) {
    if (target == null) {
      throw new NullPointerException();
    }
    return graph.get(target);
  }

  /** Given a set of target nodes, returns the build targets. */
  private static Set<BuildTarget> getTargetsFromNodes(ImmutableSet<TargetNode<?>> input) {
    Set<BuildTarget> result = new LinkedHashSet<>();
    for (TargetNode<?> node : input) {
      result.add(node.getBuildTarget());
    }
    return result;
  }

  @Override
  public Collection<BuildTarget> getFwdDeps(Iterable<BuildTarget> targets) {
    Set<BuildTarget> result = new LinkedHashSet<>();
    for (BuildTarget target : targets) {
      TargetNode<?> node = getNode(target);
      if (node != null) {
        // Using an ImmutableSortedSet in order to ensure consistent outputs because
        // getOutgoingNodesFor() returns a set whose traversal order can vary across compilers.
        result.addAll(
            getTargetsFromNodes(ImmutableSortedSet.copyOf(graph.getOutgoingNodesFor(node))));
      }
    }
    return result;
  }

  @Override
  public Collection<BuildTarget> getReverseDeps(Iterable<BuildTarget> targets) {
    throw new RuntimeException("Not implemented yet.");
  }

  @Override
  public Set<BuildTarget> getTransitiveClosure(Set<BuildTarget> targets) {
    throw new RuntimeException("Not implemented yet.");
  }

  @Override
  public void buildTransitiveClosure(
      QueryExpression caller,
      Set<BuildTarget> targetNodes,
      int maxDepth) throws QueryException, InterruptedException {
    try {
      // TODO(user): currently, this is building the graph for every transitive closure call.
      // In the future, reuse the graph between calls.
      graph = params.getParser().buildTargetGraphForBuildTargets(
          targetNodes,
          new ParserConfig(params.getBuckConfig()),
          params.getBuckEventBus(),
          params.getConsole(),
          params.getEnvironment(),
          enableProfiling);
    } catch (BuildTargetException | BuildFileParseException | IOException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      throw new QueryException("error in building depencency graph");
    }
  }

  @Override
  public Set<BuildTarget> getNodesOnPath(BuildTarget from, BuildTarget to) {
    throw new RuntimeException("Not implemented yet.");
  }

  @Nullable
  @Override
  public Set<BuildTarget> getVariable(String name) {
    return letBindings.get(name);
  }

  @Nullable
  @Override
  public Set<BuildTarget> setVariable(String name, Set<BuildTarget> value) {
    return letBindings.put(name, value);
  }

  @Override
  public boolean isSettingEnabled(Setting setting) {
    return settings.contains(Preconditions.checkNotNull(setting));
  }

  @Override
  public Iterable<QueryFunction> getFunctions() {
    return ImmutableList.of(
        DEFAULT_QUERY_FUNCTIONS.get(9),  // "deps" is the only default function supported for now.
        new QueryTestsOfFunction()
    );
  }
}
