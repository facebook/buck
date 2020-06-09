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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.impl.FilesystemBackedBuildFileTree;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.config.ParserConfig;
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
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.RdepsFunction;
import com.facebook.buck.query.TestsOfFunction;
import com.facebook.buck.query.UnconfiguredQueryBuildTarget;
import com.facebook.buck.query.UnconfiguredQueryTarget;
import com.facebook.buck.rules.param.CommonParamNames;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** QueryEnvironment implementation that operates over the unconfigured target graph. */
public class UnconfiguredQueryEnvironment
    implements EvaluatingQueryEnvironment<UnconfiguredQueryTarget> {

  private final Cell rootCell;
  private final BuckEventBus eventBus;
  private final UnconfiguredQueryTargetEvaluator targetEvaluator;
  private final UnconfiguredTargetGraph targetGraph;
  private final OwnersReport.Builder<UnconfiguredTargetNode> ownersReportBuilder;

  private final ImmutableMap<Cell, BuildFileTree> buildFileTrees;

  // This should only be modified during query execution though, so we don't need a concurrent map.
  private final Map<UnflavoredBuildTarget, UnconfiguredQueryBuildTarget> buildTargetToQueryTarget =
      new HashMap<>();

  public UnconfiguredQueryEnvironment(
      Cell rootCell,
      BuckEventBus eventBus,
      UnconfiguredQueryTargetEvaluator targetEvaluator,
      UnconfiguredTargetGraph targetGraph,
      OwnersReport.Builder<UnconfiguredTargetNode> ownersReportBuilder) {
    this.rootCell = rootCell;
    this.eventBus = eventBus;
    this.targetEvaluator = targetEvaluator;
    this.targetGraph = targetGraph;
    this.ownersReportBuilder = ownersReportBuilder;

    this.buildFileTrees =
        rootCell.getAllCells().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    Function.identity(),
                    cell ->
                        new FilesystemBackedBuildFileTree(
                            cell.getFilesystem(),
                            cell.getBuckConfigView(ParserConfig.class).getBuildFileName())));
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
    OwnersReport.Builder<UnconfiguredTargetNode> ownersReportBuilder =
        OwnersReport.builderForUnconfigured(
            params.getCells().getRootCell(), params.getClientWorkingDir(), targetGraph);

    return new UnconfiguredQueryEnvironment(
        rootCell, params.getBuckEventBus(), targetEvaluator, targetGraph, ownersReportBuilder);
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
    return targets.stream()
        .flatMap(this::filterNonBuildTargets)
        .map(this::buildfileForTarget)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public ImmutableSet<UnconfiguredQueryTarget> getFileOwners(ImmutableList<String> files) {
    OwnersReport<UnconfiguredTargetNode> report = ownersReportBuilder.build(buildFileTrees, files);
    report
        .getInputsWithNoOwners()
        .forEach(path -> eventBus.post(ConsoleEvent.warning("No owner was found for %s", path)));
    report
        .getNonExistentInputs()
        .forEach(path -> eventBus.post(ConsoleEvent.warning("File %s does not exist", path)));
    report
        .getNonFileInputs()
        .forEach(path -> eventBus.post(ConsoleEvent.warning("%s is not a regular file", path)));
    return report.owners.keySet().stream()
        .map(UnconfiguredTargetNode::getBuildTarget)
        .map(this::getOrCreateQueryBuildTarget)
        .collect(ImmutableSet.toImmutableSet());
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

  public UnconfiguredTargetNode getNode(UnconfiguredQueryBuildTarget queryBuildTarget) {
    return targetGraph.getNode(queryBuildTarget.getBuildTarget().getUnflavoredBuildTarget());
  }

  public UnconfiguredTargetNode getNode(UnconfiguredBuildTarget buildTarget) {
    return targetGraph.getNode(buildTarget.getUnflavoredBuildTarget());
  }

  public UnconfiguredTargetNode getNode(UnflavoredBuildTarget buildTarget) {
    return targetGraph.getNode(buildTarget);
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

  private QueryFileTarget buildfileForTarget(UnconfiguredQueryBuildTarget queryBuildTarget) {
    ProjectFilesystem rootCellFilesystem = rootCell.getFilesystem();
    AbsPath rootPath = rootCellFilesystem.getRootPath();

    UnconfiguredBuildTarget buildTarget = queryBuildTarget.getBuildTarget();
    Cell cell = rootCell.getCell(buildTarget.getCell());
    BuildFileTree buildFileTree = Objects.requireNonNull(buildFileTrees.get(cell));
    Optional<ForwardRelativePath> path =
        buildFileTree.getBasePathOfAncestorTarget(buildTarget.getCellRelativeBasePath().getPath());
    Preconditions.checkState(path.isPresent());

    RelPath buildFilePath =
        MorePaths.relativize(
            rootPath,
            cell.getFilesystem()
                .resolve(path.get())
                .resolve(cell.getBuckConfigView(ParserConfig.class).getBuildFileName()));
    Preconditions.checkState(rootCellFilesystem.exists(buildFilePath));
    SourcePath sourcePath = PathSourcePath.of(cell.getFilesystem(), buildFilePath);
    return QueryFileTarget.of(sourcePath);
  }

  private UnconfiguredQueryBuildTarget getOrCreateQueryBuildTarget(
      UnflavoredBuildTarget buildTarget) {
    return buildTargetToQueryTarget.computeIfAbsent(buildTarget, UnconfiguredQueryBuildTarget::of);
  }
}
