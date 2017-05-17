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

import static com.facebook.buck.util.concurrent.MoreFutures.propagateCauseIfInstanceOf;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.query.QueryTargetAccessor;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodes;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreExceptions;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * The environment of a Buck query that can evaluate queries to produce a result.
 *
 * <p>The query language is documented at docs/command/query.soy
 */
public class BuckQueryEnvironment implements QueryEnvironment {

  private static final Logger LOG = Logger.get(BuckQueryEnvironment.class);

  private final PerBuildState parserState;
  private final Cell rootCell;
  private final OwnersReport.Builder ownersReportBuilder;
  private final TargetPatternEvaluator targetPatternEvaluator;

  private final Map<Cell, BuildFileTree> buildFileTrees = new HashMap<>();
  private final Map<BuildTarget, QueryTarget> buildTargetToQueryTarget = new HashMap<>();

  // Query execution is single threaded, however the buildTransitiveClosure implementation
  // traverses the graph in parallel.
  private MutableDirectedGraph<TargetNode<?, ?>> graph = MutableDirectedGraph.createConcurrent();
  private Map<BuildTarget, TargetNode<?, ?>> targetsToNodes = new ConcurrentHashMap<>();

  private BuckQueryEnvironment(
      Cell rootCell,
      OwnersReport.Builder ownersReportBuilder,
      PerBuildState parserState,
      TargetPatternEvaluator targetPatternEvaluator) {
    this.parserState = parserState;
    this.rootCell = rootCell;
    this.ownersReportBuilder = ownersReportBuilder;
    this.buildFileTrees.put(
        rootCell,
        new FilesystemBackedBuildFileTree(rootCell.getFilesystem(), rootCell.getBuildFileName()));
    this.targetPatternEvaluator = targetPatternEvaluator;
  }

  public static BuckQueryEnvironment from(
      Cell rootCell,
      OwnersReport.Builder ownersReportBuilder,
      PerBuildState parserState,
      TargetPatternEvaluator targetPatternEvaluator) {
    return new BuckQueryEnvironment(
        rootCell, ownersReportBuilder, parserState, targetPatternEvaluator);
  }

  public static BuckQueryEnvironment from(
      CommandRunnerParams params, PerBuildState parserState, boolean enableProfiling) {
    return from(
        params.getCell(),
        OwnersReport.builder(
            params.getCell(), params.getParser(), params.getBuckEventBus(), params.getConsole()),
        parserState,
        new TargetPatternEvaluator(
            params.getCell(),
            params.getBuckConfig(),
            params.getParser(),
            params.getBuckEventBus(),
            enableProfiling));
  }

  public DirectedAcyclicGraph<TargetNode<?, ?>> getTargetGraph() {
    return new DirectedAcyclicGraph<>(graph);
  }

  public PerBuildState getParserState() {
    return parserState;
  }

  public void preloadTargetPatterns(Iterable<String> patterns, ListeningExecutorService executor)
      throws QueryException, InterruptedException {
    try {
      targetPatternEvaluator.preloadTargetPatterns(patterns, executor);
    } catch (IOException e) {
      throw new QueryException(
          e, "Error in preloading targets. %s: %s", e.getClass(), e.getMessage());
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new QueryException(e, "Error in preloading targets. %s", e.getMessage());
    }
  }

  /**
   * Evaluate the specified query expression in this environment.
   *
   * @return the resulting set of targets.
   * @throws QueryException if the evaluation failed.
   */
  public ImmutableSet<QueryTarget> evaluateQuery(
      QueryExpression expr, ListeningExecutorService executor)
      throws QueryException, InterruptedException {
    Set<String> targetLiterals = new HashSet<>();
    expr.collectTargetPatterns(targetLiterals);
    preloadTargetPatterns(targetLiterals, executor);
    return expr.eval(this, executor);
  }

  public ImmutableSet<QueryTarget> evaluateQuery(String query, ListeningExecutorService executor)
      throws QueryException, InterruptedException {
    return evaluateQuery(QueryExpression.parse(query, this), executor);
  }

  @Override
  public ImmutableSet<QueryTarget> getTargetsMatchingPattern(
      String pattern, ListeningExecutorService executor)
      throws QueryException, InterruptedException {
    try {
      return ImmutableSet.copyOf(
          Iterables.concat(
              targetPatternEvaluator
                  .resolveTargetPatterns(ImmutableList.of(pattern), executor)
                  .values()));
    } catch (BuildTargetException | BuildFileParseException | IOException e) {
      throw new QueryException(e, "Error in resolving targets matching %s", pattern);
    }
  }

  TargetNode<?, ?> getNode(QueryTarget target) throws QueryException {
    if (!(target instanceof QueryBuildTarget)) {
      throw new IllegalArgumentException(
          String.format(
              "Expected %s to be a build target but it was an instance of %s",
              target, target.getClass().getName()));
    }
    try {
      return parserState.getTargetNode(((QueryBuildTarget) target).getBuildTarget());
    } catch (BuildTargetException | BuildFileParseException e) {
      throw new QueryException(e, "Error getting target node for %s\n%s", target, e.getMessage());
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

  public ImmutableSet<QueryTarget> getTargetsFromTargetNodes(
      Iterable<TargetNode<?, ?>> targetNodes) {
    ImmutableSortedSet.Builder<QueryTarget> builder = ImmutableSortedSet.naturalOrder();
    for (TargetNode<?, ?> targetNode : targetNodes) {
      builder.add(getOrCreateQueryBuildTarget(targetNode.getBuildTarget()));
    }
    return builder.build();
  }

  public ImmutableSet<QueryTarget> getTargetsFromBuildTargets(Iterable<BuildTarget> buildTargets) {
    ImmutableSortedSet.Builder<QueryTarget> builder = ImmutableSortedSet.naturalOrder();
    for (BuildTarget buildTarget : buildTargets) {
      builder.add(getOrCreateQueryBuildTarget(buildTarget));
    }
    return builder.build();
  }

  public ImmutableSet<TargetNode<?, ?>> getNodesFromQueryTargets(Iterable<QueryTarget> input)
      throws QueryException {
    ImmutableSet.Builder<TargetNode<?, ?>> builder = ImmutableSet.builder();
    for (QueryTarget target : input) {
      builder.add(getNode(target));
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<QueryTarget> getFwdDeps(Iterable<QueryTarget> targets)
      throws QueryException, InterruptedException {
    ImmutableSet.Builder<QueryTarget> result = new ImmutableSet.Builder<>();
    for (QueryTarget target : targets) {
      TargetNode<?, ?> node = getNode(target);
      result.addAll(getTargetsFromTargetNodes(graph.getOutgoingNodesFor(node)));
    }
    return result.build();
  }

  @Override
  public Set<QueryTarget> getReverseDeps(Iterable<QueryTarget> targets)
      throws QueryException, InterruptedException {
    Set<QueryTarget> result = new LinkedHashSet<>();
    for (QueryTarget target : targets) {
      TargetNode<?, ?> node = getNode(target);
      result.addAll(getTargetsFromTargetNodes(graph.getIncomingNodesFor(node)));
    }
    return result;
  }

  @Override
  public Set<QueryTarget> getInputs(QueryTarget target) throws QueryException {
    TargetNode<?, ?> node = getNode(target);
    return node.getInputs()
        .stream()
        .map(QueryFileTarget::of)
        .collect(MoreCollectors.toImmutableSet());
  }

  @Override
  public ImmutableSet<QueryTarget> getTransitiveClosure(Set<QueryTarget> targets)
      throws QueryException, InterruptedException {
    Set<TargetNode<?, ?>> nodes = new LinkedHashSet<>();
    for (QueryTarget target : targets) {
      nodes.add(getNode(target));
    }
    ImmutableSet.Builder<QueryTarget> result = ImmutableSet.builder();

    new AbstractBreadthFirstTraversal<TargetNode<?, ?>>(nodes) {
      @Override
      public ImmutableSet<TargetNode<?, ?>> visit(TargetNode<?, ?> node) {
        result.add(getOrCreateQueryBuildTarget(node.getBuildTarget()));
        return node.getParseDeps()
            .stream()
            .map(targetsToNodes::get)
            .collect(MoreCollectors.toImmutableSet());
      }
    }.start();

    return result.build();
  }

  @Override
  public void buildTransitiveClosure(
      Set<QueryTarget> targets, int maxDepth, ListeningExecutorService executor)
      throws QueryException, InterruptedException {
    // Filter QueryTargets that are build targets and not yet present in the build target graph.
    ImmutableSet<BuildTarget> newBuildTargets =
        targets
            .stream()
            .filter(target -> target instanceof QueryBuildTarget)
            .map(target -> ((QueryBuildTarget) target).getBuildTarget())
            .filter(buildTarget -> !targetsToNodes.containsKey(buildTarget))
            .collect(MoreCollectors.toImmutableSet());

    // TODO(mkosiba): This looks more and more like the Parser.buildTargetGraph method. Unify the
    // two.

    ConcurrentHashMap<BuildTarget, ListenableFuture<Void>> jobsCache = new ConcurrentHashMap<>();

    try {
      List<ListenableFuture<Void>> depsFuture = new ArrayList<>();
      for (BuildTarget buildTarget : newBuildTargets) {
        discoverNewTargetsConcurrently(buildTarget, jobsCache)
            .ifPresent(dep -> depsFuture.add(dep));
      }
      Futures.allAsList(depsFuture).get();
    } catch (ExecutionException e) {
      if (e.getCause() != null) {
        throw new QueryException(e.getCause(), "Failed parsing: " + e.getLocalizedMessage());
      }
      propagateCauseIfInstanceOf(e, ExecutionException.class);
      propagateCauseIfInstanceOf(e, UncheckedExecutionException.class);
    } catch (BuildFileParseException | BuildTargetException e) {
      throw new QueryException(
          e, "Failed parsing: " + MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }

    GraphTraversable<BuildTarget> traversable =
        target -> {
          TargetNode<?, ?> node =
              Preconditions.checkNotNull(
                  targetsToNodes.get(target),
                  "Node %s should have been discovered by `discoverNewTargetsConcurrently`.",
                  target);

          // If a node has been added to the graph it means it and all of its children have been
          // visited by an acyclic traversal and added to the graph. From this it follows that there
          // are no outgoing edges from the graph (as it had been "fully" explored before) back out
          // to the set of nodes we're currently exploring. Based on that:
          //  - we can't have a cycle involving the "old" nodes,
          //  - there are no new edges or nodes to be discovered by descending into the "old" nodes,
          // making this node safe to skip.
          if (graph.getNodes().contains(node)) {
            return ImmutableSet.<BuildTarget>of().iterator();
          }
          return node.getParseDeps().iterator();
        };

    AcyclicDepthFirstPostOrderTraversal<BuildTarget> targetNodeTraversal =
        new AcyclicDepthFirstPostOrderTraversal<>(traversable);
    try {
      for (BuildTarget buildTarget : targetNodeTraversal.traverse(newBuildTargets)) {
        TargetNode<?, ?> node =
            Preconditions.checkNotNull(
                targetsToNodes.get(buildTarget), "Couldn't find TargetNode for %s", buildTarget);
        graph.addNode(node);
        for (BuildTarget dep : node.getParseDeps()) {
          graph.addEdge(
              node,
              Preconditions.checkNotNull(
                  targetsToNodes.get(dep), "Couldn't find TargetNode for %s", dep));
        }
      }
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new QueryException(e, e.getMessage());
    }

    for (BuildTarget buildTarget : jobsCache.keySet()) {
      if (!buildTargetToQueryTarget.containsKey(buildTarget)) {
        buildTargetToQueryTarget.put(buildTarget, QueryBuildTarget.of(buildTarget));
      }
    }
  }

  private Optional<ListenableFuture<Void>> discoverNewTargetsConcurrently(
      BuildTarget buildTarget, ConcurrentHashMap<BuildTarget, ListenableFuture<Void>> jobsCache)
      throws InterruptedException, QueryException, BuildFileParseException, BuildTargetException {
    ListenableFuture<Void> job = jobsCache.get(buildTarget);
    if (job != null) {
      return Optional.empty();
    }
    SettableFuture<Void> newJob = SettableFuture.create();
    if (jobsCache.putIfAbsent(buildTarget, newJob) != null) {
      return Optional.empty();
    }

    ListenableFuture<Void> future =
        Futures.transformAsync(
            parserState.getTargetNodeJob(buildTarget),
            targetNode -> {
              targetsToNodes.put(buildTarget, targetNode);
              List<ListenableFuture<Void>> depsFuture = new ArrayList<>();
              final Set<BuildTarget> parseDeps = targetNode.getParseDeps();
              for (BuildTarget parseDep : parseDeps) {
                discoverNewTargetsConcurrently(parseDep, jobsCache)
                    .ifPresent(
                        depWork ->
                            depsFuture.add(
                                attachParentNodeToErrorMessage(buildTarget, parseDep, depWork)));
              }
              return Futures.transform(Futures.allAsList(depsFuture), Functions.constant(null));
            });
    newJob.setFuture(future);
    return Optional.of(newJob);
  }

  private static ListenableFuture<Void> attachParentNodeToErrorMessage(
      BuildTarget buildTarget, BuildTarget parseDep, ListenableFuture<Void> depWork) {
    return Futures.catchingAsync(
        depWork,
        Exception.class,
        exceptionInput -> {
          if (exceptionInput instanceof BuildTargetException
              || exceptionInput instanceof BuildFileParseException) {
            throw new HumanReadableException(
                exceptionInput,
                "Couldn't get dependency '%s' of target '%s':\n%s",
                parseDep,
                buildTarget,
                exceptionInput.getMessage());
          }
          throw exceptionInput;
        });
  }

  @Override
  public ImmutableSet<QueryTarget> getTestsForTarget(QueryTarget target)
      throws QueryException, InterruptedException {
    return getTargetsFromBuildTargets(TargetNodes.getTestTargetsForNode(getNode(target)));
  }

  @Override
  public ImmutableSet<QueryTarget> getBuildFiles(Set<QueryTarget> targets) throws QueryException {
    final ProjectFilesystem cellFilesystem = rootCell.getFilesystem();
    final Path rootPath = cellFilesystem.getRootPath();
    Preconditions.checkState(rootPath.isAbsolute());

    ImmutableSet.Builder<QueryTarget> builder = ImmutableSet.builder();
    for (QueryTarget target : targets) {
      Preconditions.checkState(target instanceof QueryBuildTarget);
      BuildTarget buildTarget = ((QueryBuildTarget) target).getBuildTarget();
      Cell cell = rootCell.getCell(buildTarget);

      if (!buildFileTrees.containsKey(cell)) {
        LOG.info("Creating a new filesystem-backed build file tree for %s", cell.getRoot());
        buildFileTrees.put(
            cell, new FilesystemBackedBuildFileTree(cell.getFilesystem(), cell.getBuildFileName()));
      }
      BuildFileTree buildFileTree = Preconditions.checkNotNull(buildFileTrees.get(cell));
      Optional<Path> path = buildFileTree.getBasePathOfAncestorTarget(buildTarget.getBasePath());
      Preconditions.checkState(path.isPresent());

      Path buildFilePath =
          MorePaths.relativize(
              rootPath, cell.getFilesystem().resolve(path.get()).resolve(cell.getBuildFileName()));
      Preconditions.checkState(cellFilesystem.exists(buildFilePath));
      builder.add(QueryFileTarget.of(buildFilePath));
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<QueryTarget> getFileOwners(
      ImmutableList<String> files, ListeningExecutorService executor)
      throws InterruptedException, QueryException {
    try {
      BuildFileTree buildFileTree = Preconditions.checkNotNull(buildFileTrees.get(rootCell));
      OwnersReport report = ownersReportBuilder.build(buildFileTree, executor, files);
      return getTargetsFromTargetNodes(report.owners.keySet());
    } catch (BuildFileParseException | IOException e) {
      throw new QueryException(e, "Could not parse build targets.\n%s", e.getMessage());
    }
  }

  @Override
  public String getTargetKind(QueryTarget target) throws QueryException, InterruptedException {
    return Description.getBuildRuleType(getNode(target).getDescription()).getName();
  }

  @Override
  public ImmutableSet<QueryTarget> getTargetsInAttribute(QueryTarget target, String attribute)
      throws QueryException, InterruptedException {
    return QueryTargetAccessor.getTargetsInAttribute(getNode(target), attribute);
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      QueryTarget target, String attribute, final Predicate<Object> predicate)
      throws QueryException, InterruptedException {
    return QueryTargetAccessor.filterAttributeContents(getNode(target), attribute, predicate);
  }

  @Override
  public Iterable<QueryFunction> getFunctions() {
    return DEFAULT_QUERY_FUNCTIONS;
  }
}
