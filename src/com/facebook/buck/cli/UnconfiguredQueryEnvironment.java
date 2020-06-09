/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.query.AllPathsFunction;
import com.facebook.buck.query.AttrFilterFunction;
import com.facebook.buck.query.AttrRegexFilterFunction;
import com.facebook.buck.query.BuildFileFunction;
import com.facebook.buck.query.DepsFunction;
import com.facebook.buck.query.EvaluatingQueryEnvironment;
import com.facebook.buck.query.FilterFunction;
import com.facebook.buck.query.InputsFunction;
import com.facebook.buck.query.KindFunction;
import com.facebook.buck.query.LabelsFunction;
import com.facebook.buck.query.NoopQueryEvaluator;
import com.facebook.buck.query.OwnerFunction;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.RdepsFunction;
import com.facebook.buck.query.TestsOfFunction;
import com.facebook.buck.query.UnconfiguredQueryBuildTarget;
import com.facebook.buck.query.UnconfiguredQueryTarget;
import com.facebook.buck.rules.param.CommonParamNames;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** QueryEnvironment implementation that operates over the unconfigured target graph. */
public class UnconfiguredQueryEnvironment
    implements EvaluatingQueryEnvironment<UnconfiguredQueryTarget> {

  private final UnconfiguredQueryTargetEvaluator targetEvaluator;
  private final UnconfiguredTargetGraph targetGraph;
  // This should only be modified during query execution though, so we don't need a concurrent map.
  private final Map<UnflavoredBuildTarget, UnconfiguredQueryBuildTarget> buildTargetToQueryTarget =
      new HashMap<>();

  public UnconfiguredQueryEnvironment(
      UnconfiguredQueryTargetEvaluator targetEvaluator, UnconfiguredTargetGraph targetGraph) {
    this.targetEvaluator = targetEvaluator;
    this.targetGraph = targetGraph;
  }

  /** Convenience constructor */
  public static UnconfiguredQueryEnvironment from(
      CommandRunnerParams params, PerBuildState perBuildState) {
    Cell rootCell = params.getCells().getRootCell();
    Parser parser = params.getParser();
    UnconfiguredQueryTargetEvaluator targetEvaluator =
        UnconfiguredQueryTargetEvaluator.from(
            parser,
            perBuildState,
            params.getCells().getRootCell(),
            params.getClientWorkingDir(),
            params.getBuckConfig());
    UnconfiguredTargetGraph targetGraph =
        new UnconfiguredTargetGraph(
            parser,
            perBuildState,
            rootCell,
            params.getKnownRuleTypesProvider(),
            params.getTypeCoercerFactory());

    return new UnconfiguredQueryEnvironment(targetEvaluator, targetGraph);
  }

  @Override
  public Set<UnconfiguredQueryTarget> evaluateQuery(QueryExpression<UnconfiguredQueryTarget> expr)
      throws QueryException, InterruptedException {
    Set<String> targetLiterals = new HashSet<>();
    expr.collectTargetPatterns(targetLiterals);
    preloadTargetPatterns(targetLiterals);
    return new NoopQueryEvaluator<UnconfiguredQueryTarget>().eval(expr, this);
  }

  @Override
  public void preloadTargetPatterns(Iterable<String> patterns)
      throws QueryException, InterruptedException {
    for (String pattern : patterns) {
      targetEvaluator.evaluateTarget(pattern);
    }
  }

  @Override
  public Iterable<QueryFunction<UnconfiguredQueryTarget>> getFunctions() {
    return ImmutableList.of(
        new AllPathsFunction<>(),
        new AttrFilterFunction<>(),
        new AttrRegexFilterFunction<>(),
        new BuildFileFunction<>(),
        new DepsFunction<>(),
        new DepsFunction.FirstOrderDepsFunction<>(),
        new DepsFunction.LookupFunction<>(),
        new InputsFunction<>(),
        new FilterFunction<>(),
        new KindFunction<>(),
        new LabelsFunction<>(),
        new OwnerFunction<>(),
        new RdepsFunction<>(),
        new TestsOfFunction<>());
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getTransitiveClosure(
      Set<UnconfiguredQueryTarget> targets) {
    Set<UnconfiguredTargetNode> nodes = new LinkedHashSet<>(targets.size());
    for (UnconfiguredQueryTarget queryTarget : targets) {
      if (queryTarget instanceof UnconfiguredQueryBuildTarget) {
        nodes.add(getNode((UnconfiguredQueryBuildTarget) queryTarget));
      }
    }

    ImmutableSet.Builder<UnconfiguredQueryTarget> result = ImmutableSet.builder();

    new AbstractBreadthFirstTraversal<UnconfiguredTargetNode>(nodes) {
      @Override
      public Iterable<UnconfiguredTargetNode> visit(UnconfiguredTargetNode node) {
        result.add(getOrCreateQueryBuildTarget(node.getBuildTarget()));
        return targetGraph.getTraversalResult(node).getParseDeps().stream()
            .map((buildTarget) -> getNode(buildTarget.getUnflavoredBuildTarget()))
            .collect(ImmutableSet.toImmutableSet());
      }
    }.start();

    return result.build();
  }

  @Override
  public void buildTransitiveClosure(Set<UnconfiguredQueryTarget> targets) throws QueryException {
    ImmutableSet<UnconfiguredBuildTarget> buildTargets =
        targets.stream()
            .flatMap(this::filterNonBuildTargets)
            .map(UnconfiguredQueryBuildTarget::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet());
    targetGraph.buildTransitiveClosure(buildTargets);
  }

  @Override
  public QueryEnvironment.TargetEvaluator<UnconfiguredQueryTarget> getTargetEvaluator() {
    return targetEvaluator;
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getFwdDeps(Iterable<UnconfiguredQueryTarget> targets)
      throws QueryException {
    return Streams.stream(targets)
        .flatMap(this::filterNonBuildTargets)
        .map(UnconfiguredQueryBuildTarget::getBuildTarget)
        .map(targetGraph::assertCachedNode)
        .flatMap(n -> Streams.stream(targetGraph.getOutgoingNodesFor(n)))
        .map(UnconfiguredTargetNode::getBuildTarget)
        .map(this::getOrCreateQueryBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public Set<UnconfiguredQueryTarget> getReverseDeps(Iterable<UnconfiguredQueryTarget> targets)
      throws QueryException {
    return Streams.stream(targets)
        .flatMap(this::filterNonBuildTargets)
        .map(UnconfiguredQueryBuildTarget::getBuildTarget)
        .map(targetGraph::assertCachedNode)
        .flatMap(n -> Streams.stream(targetGraph.getIncomingNodesFor(n)))
        .map(UnconfiguredTargetNode::getBuildTarget)
        .map(this::getOrCreateQueryBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public Set<UnconfiguredQueryTarget> getInputs(UnconfiguredQueryTarget target)
      throws QueryException {
    UnconfiguredTargetNode node = getNode((UnconfiguredQueryBuildTarget) target);
    return ImmutableSet.copyOf(targetGraph.getTraversalResult(node).getInputs());
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getTestsForTarget(UnconfiguredQueryTarget target)
      throws QueryException {
    // NOTE: For configured queries this works by looking for the `HasTests` interface. It's
    // possible that a rule has a `tests` attribute but doesn't use the `HasTests` interface,
    // leading to inconsistent results.
    return getTargetsInAttribute(target, CommonParamNames.TESTS);
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getBuildFiles(Set<UnconfiguredQueryTarget> targets) {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getFileOwners(ImmutableList<String> files) {
    throw new RuntimeException("Not yet implemented");
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getConfiguredTargets(
      Set<UnconfiguredQueryTarget> targets, Optional<String> configurationName)
      throws QueryException {
    throw new UnsupportedOperationException(
        "Calls to `getConfiguredTargets` are not valid for UnconfiguredQueryEnvironment");
  }

  @Override
  public String getTargetKind(UnconfiguredQueryTarget target) throws QueryException {
    UnconfiguredBuildTarget buildTarget = ((UnconfiguredQueryBuildTarget) target).getBuildTarget();
    return getNode(buildTarget).getRuleType().getName();
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getTargetsInAttribute(
      UnconfiguredQueryTarget target, ParamName attribute) throws QueryException {
    UnconfiguredTargetNode node = getNode((UnconfiguredQueryBuildTarget) target);
    return targetGraph
        .getTraversalResult(node)
        .getTargetsByParam()
        .getOrDefault(attribute, ImmutableSet.of());
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      UnconfiguredQueryTarget target, ParamName attribute, Predicate<Object> predicate)
      throws QueryException {
    UnconfiguredTargetNode node = getNode((UnconfiguredQueryBuildTarget) target);
    return targetGraph.filterAttributeContents(node, attribute, predicate);
  }

  /**
   * Filter function to remove any {@code UnconfiguredQueryTarget}s that don't refer to build
   * targets
   */
  private Stream<UnconfiguredQueryBuildTarget> filterNonBuildTargets(
      UnconfiguredQueryTarget queryTarget) {
    if (queryTarget instanceof UnconfiguredQueryBuildTarget) {
      return Stream.of((UnconfiguredQueryBuildTarget) queryTarget);
    } else {
      return Stream.of();
    }
  }

  private UnconfiguredQueryBuildTarget getOrCreateQueryBuildTarget(
      UnflavoredBuildTarget buildTarget) {
    return buildTargetToQueryTarget.computeIfAbsent(buildTarget, UnconfiguredQueryBuildTarget::of);
  }

  private UnconfiguredTargetNode getNode(UnconfiguredQueryBuildTarget queryBuildTarget) {
    return targetGraph.getNode(queryBuildTarget.getBuildTarget().getUnflavoredBuildTarget());
  }

  private UnconfiguredTargetNode getNode(UnconfiguredBuildTarget buildTarget) {
    return targetGraph.getNode(buildTarget.getUnflavoredBuildTarget());
  }

  private UnconfiguredTargetNode getNode(UnflavoredBuildTarget buildTarget) {
    return targetGraph.getNode(buildTarget);
  }
}
