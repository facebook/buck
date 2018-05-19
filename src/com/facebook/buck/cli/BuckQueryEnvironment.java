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

import com.facebook.buck.cli.OwnersReport.Builder;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.DirectedAcyclicGraph;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserMessages;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.query.NoopQueryEvaluator;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryEnvironment;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.query.QueryExpression;
import com.facebook.buck.query.QueryFileTarget;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.query.QueryTargetAccessor;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.MoreExceptions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The environment of a Buck query that can evaluate queries to produce a result.
 *
 * <p>The query language is documented at docs/command/query.soy
 */
public class BuckQueryEnvironment implements QueryEnvironment {

  private final Parser parser;
  private final PerBuildState parserState;
  private final Cell rootCell;
  private final OwnersReport.Builder ownersReportBuilder;
  private final ListeningExecutorService executor;
  private final TargetPatternEvaluator targetPatternEvaluator;
  private final BuckEventBus eventBus;
  private final QueryEnvironment.TargetEvaluator queryTargetEvaluator;
  private final TypeCoercerFactory typeCoercerFactory;

  private final ImmutableMap<Cell, BuildFileTree> buildFileTrees;
  private final Map<BuildTarget, QueryTarget> buildTargetToQueryTarget = new HashMap<>();

  // Query execution is single threaded, however the buildTransitiveClosure implementation
  // traverses the graph in parallel.
  private MutableDirectedGraph<TargetNode<?, ?>> graph = MutableDirectedGraph.createConcurrent();
  private Map<BuildTarget, TargetNode<?, ?>> targetsToNodes = new ConcurrentHashMap<>();

  @VisibleForTesting
  protected BuckQueryEnvironment(
      Cell rootCell,
      Builder ownersReportBuilder,
      Parser parser,
      PerBuildState parserState,
      ListeningExecutorService executor,
      TargetPatternEvaluator targetPatternEvaluator,
      BuckEventBus eventBus,
      TypeCoercerFactory typeCoercerFactory) {
    this.parser = parser;
    this.eventBus = eventBus;
    this.parserState = parserState;
    this.rootCell = rootCell;
    this.ownersReportBuilder = ownersReportBuilder;
    this.buildFileTrees =
        rootCell
            .getAllCells()
            .stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    Function.identity(),
                    cell ->
                        new FilesystemBackedBuildFileTree(
                            cell.getFilesystem(), cell.getBuildFileName())));
    this.executor = executor;
    this.targetPatternEvaluator = targetPatternEvaluator;
    this.queryTargetEvaluator = new TargetEvaluator(targetPatternEvaluator, executor);
    this.typeCoercerFactory = typeCoercerFactory;
  }

  public static BuckQueryEnvironment from(
      Cell rootCell,
      OwnersReport.Builder ownersReportBuilder,
      Parser parser,
      PerBuildState parserState,
      ListeningExecutorService executor,
      TargetPatternEvaluator targetPatternEvaluator,
      BuckEventBus eventBus,
      TypeCoercerFactory typeCoercerFactory) {
    return new BuckQueryEnvironment(
        rootCell,
        ownersReportBuilder,
        parser,
        parserState,
        executor,
        targetPatternEvaluator,
        eventBus,
        typeCoercerFactory);
  }

  public static BuckQueryEnvironment from(
      CommandRunnerParams params,
      PerBuildState parserState,
      ListeningExecutorService executor,
      boolean enableProfiling) {
    return from(
        params.getCell(),
        OwnersReport.builder(params.getCell(), params.getParser(), params.getBuckEventBus()),
        params.getParser(),
        parserState,
        executor,
        new TargetPatternEvaluator(
            params.getCell(),
            params.getBuckConfig(),
            params.getParser(),
            params.getBuckEventBus(),
            enableProfiling),
        params.getBuckEventBus(),
        params.getTypeCoercerFactory());
  }

  public DirectedAcyclicGraph<TargetNode<?, ?>> getTargetGraph() {
    return new DirectedAcyclicGraph<>(graph);
  }

  public PerBuildState getParserState() {
    return parserState;
  }

  public void preloadTargetPatterns(Iterable<String> patterns)
      throws QueryException, InterruptedException {
    try {
      targetPatternEvaluator.preloadTargetPatterns(patterns, executor);
    } catch (IOException e) {
      throw new QueryException(
          e, "Error in preloading targets. %s: %s", e.getClass(), e.getMessage());
    } catch (BuildFileParseException e) {
      throw new QueryException(e, "Error in preloading targets. %s", e.getMessage());
    }
  }

  /**
   * Evaluate the specified query expression in this environment.
   *
   * @return the resulting set of targets.
   * @throws QueryException if the evaluation failed.
   */
  public ImmutableSet<QueryTarget> evaluateQuery(QueryExpression expr)
      throws QueryException, InterruptedException {
    Set<String> targetLiterals = new HashSet<>();
    expr.collectTargetPatterns(targetLiterals);
    preloadTargetPatterns(targetLiterals);
    return new NoopQueryEvaluator().eval(expr, this);
  }

  public ImmutableSet<QueryTarget> evaluateQuery(String query)
      throws QueryException, InterruptedException {
    return evaluateQuery(QueryExpression.parse(query, this));
  }

  TargetNode<?, ?> getNode(QueryTarget target) throws QueryException {
    if (!(target instanceof QueryBuildTarget)) {
      throw new IllegalArgumentException(
          String.format(
              "Expected %s to be a build target but it was an instance of %s",
              target, target.getClass().getName()));
    }
    try {
      return parser.getTargetNode(parserState, ((QueryBuildTarget) target).getBuildTarget());
    } catch (BuildFileParseException e) {
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
  public ImmutableSet<QueryTarget> getFwdDeps(Iterable<QueryTarget> targets) throws QueryException {
    ImmutableSet.Builder<QueryTarget> result = new ImmutableSet.Builder<>();
    for (QueryTarget target : targets) {
      TargetNode<?, ?> node = getNode(target);
      result.addAll(getTargetsFromTargetNodes(graph.getOutgoingNodesFor(node)));
    }
    return result.build();
  }

  @Override
  public Set<QueryTarget> getReverseDeps(Iterable<QueryTarget> targets) throws QueryException {
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
        .map(path -> PathSourcePath.of(node.getFilesystem(), path))
        .map(QueryFileTarget::of)
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public ImmutableSet<QueryTarget> getTransitiveClosure(Set<QueryTarget> targets)
      throws QueryException {
    Set<TargetNode<?, ?>> nodes = new LinkedHashSet<>();
    for (QueryTarget target : targets) {
      nodes.add(getNode(target));
    }
    ImmutableSet.Builder<QueryTarget> result = ImmutableSet.builder();

    new AbstractBreadthFirstTraversal<TargetNode<?, ?>>(nodes) {
      @Override
      public Iterable<TargetNode<?, ?>> visit(TargetNode<?, ?> node) {
        result.add(getOrCreateQueryBuildTarget(node.getBuildTarget()));
        return node.getParseDeps()
            .stream()
            .map(targetsToNodes::get)
            .collect(ImmutableSet.toImmutableSet());
      }
    }.start();

    return result.build();
  }

  @Override
  public void buildTransitiveClosure(Set<QueryTarget> targets, int maxDepth) throws QueryException {
    // Filter QueryTargets that are build targets and not yet present in the build target graph.
    ImmutableSet<BuildTarget> newBuildTargets =
        targets
            .stream()
            .filter(target -> target instanceof QueryBuildTarget)
            .map(target -> ((QueryBuildTarget) target).getBuildTarget())
            .filter(buildTarget -> !targetsToNodes.containsKey(buildTarget))
            .collect(ImmutableSet.toImmutableSet());

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
    } catch (BuildFileParseException | InterruptedException e) {
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
      throws BuildFileParseException {
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
            parser.getTargetNodeJob(parserState, buildTarget),
            targetNode -> {
              targetsToNodes.put(buildTarget, targetNode);
              List<ListenableFuture<Void>> depsFuture = new ArrayList<>();
              Set<BuildTarget> parseDeps = targetNode.getParseDeps();
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
            if (exceptionInput instanceof BuildTargetException) {
              throw ParserMessages.createReadableExceptionWithWhenSuffix(
                  buildTarget, parseDep, (BuildTargetException) exceptionInput);
            } else if (exceptionInput instanceof BuildFileParseException) {
              throw ParserMessages.createReadableExceptionWithWhenSuffix(
                  buildTarget, parseDep, (BuildFileParseException) exceptionInput);
            } else {
              Preconditions.checkState(false, "Unknown exception type");
            }
          }
          throw exceptionInput;
        });
  }

  @Override
  public ImmutableSet<QueryTarget> getTestsForTarget(QueryTarget target) throws QueryException {
    return getTargetsFromBuildTargets(TargetNodes.getTestTargetsForNode(getNode(target)));
  }

  @Override
  public ImmutableSet<QueryTarget> getBuildFiles(Set<QueryTarget> targets) {
    ProjectFilesystem cellFilesystem = rootCell.getFilesystem();
    Path rootPath = cellFilesystem.getRootPath();
    Preconditions.checkState(rootPath.isAbsolute());

    ImmutableSet.Builder<QueryTarget> builder = ImmutableSet.builder();
    for (QueryTarget target : targets) {
      Preconditions.checkState(target instanceof QueryBuildTarget);
      BuildTarget buildTarget = ((QueryBuildTarget) target).getBuildTarget();
      Cell cell = rootCell.getCell(buildTarget);
      BuildFileTree buildFileTree = Preconditions.checkNotNull(buildFileTrees.get(cell));
      Optional<Path> path = buildFileTree.getBasePathOfAncestorTarget(buildTarget.getBasePath());
      Preconditions.checkState(path.isPresent());

      Path buildFilePath =
          MorePaths.relativize(
              rootPath, cell.getFilesystem().resolve(path.get()).resolve(cell.getBuildFileName()));
      Preconditions.checkState(cellFilesystem.exists(buildFilePath));
      SourcePath sourcePath = PathSourcePath.of(cell.getFilesystem(), buildFilePath);
      builder.add(QueryFileTarget.of(sourcePath));
    }
    return builder.build();
  }

  @Override
  public ImmutableSet<QueryTarget> getFileOwners(ImmutableList<String> files) {
    OwnersReport report = ownersReportBuilder.build(buildFileTrees, executor, files);
    report
        .getInputsWithNoOwners()
        .forEach(path -> eventBus.post(ConsoleEvent.warning("No owner was found for %s", path)));
    report
        .getNonExistentInputs()
        .forEach(path -> eventBus.post(ConsoleEvent.warning("File %s does not exist", path)));
    report
        .getNonFileInputs()
        .forEach(path -> eventBus.post(ConsoleEvent.warning("%s is not a regular file", path)));
    return getTargetsFromTargetNodes(report.owners.keySet());
  }

  @Override
  public String getTargetKind(QueryTarget target) throws QueryException {
    return getNode(target).getBuildRuleType().getName();
  }

  @Override
  public ImmutableSet<QueryTarget> getTargetsInAttribute(QueryTarget target, String attribute)
      throws QueryException {
    return QueryTargetAccessor.getTargetsInAttribute(
        typeCoercerFactory, getNode(target), attribute);
  }

  @Override
  public ImmutableSet<Object> filterAttributeContents(
      QueryTarget target, String attribute, Predicate<Object> predicate) throws QueryException {
    return QueryTargetAccessor.filterAttributeContents(
        typeCoercerFactory, getNode(target), attribute, predicate);
  }

  @Override
  public Iterable<QueryFunction> getFunctions() {
    return DEFAULT_QUERY_FUNCTIONS;
  }

  @Override
  public QueryEnvironment.TargetEvaluator getTargetEvaluator() {
    return queryTargetEvaluator;
  }

  private static class TargetEvaluator implements QueryEnvironment.TargetEvaluator {
    private final TargetPatternEvaluator evaluator;
    private final ListeningExecutorService executor;

    private TargetEvaluator(TargetPatternEvaluator evaluator, ListeningExecutorService executor) {
      this.evaluator = evaluator;
      this.executor = executor;
    }

    @Override
    public ImmutableSet<QueryTarget> evaluateTarget(String target) throws QueryException {
      try {
        return ImmutableSet.copyOf(
            Iterables.concat(
                evaluator.resolveTargetPatterns(ImmutableList.of(target), executor).values()));
      } catch (BuildFileParseException | InterruptedException | IOException e) {
        throw new QueryException(e, "Error in resolving targets matching %s", target);
      }
    }

    @Override
    public Type getType() {
      return Type.LAZY;
    }
  }
}
