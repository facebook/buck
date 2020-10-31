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

import static com.facebook.buck.util.concurrent.MoreFutures.propagateCauseIfInstanceOf;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.core.util.graph.ConsumingTraverser;
import com.facebook.buck.core.util.graph.CycleException;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.graph.TraversableGraph;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserMessages;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.parser.temporarytargetuniquenesschecker.TemporaryUnconfiguredTargetToTargetUniquenessChecker;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * A `TargetUniverse` implementation that parses new files as necessary. This is the target universe
 * used unconditionally by legacy `buck query`, which will be replaced by `cquery` and `uquery`.
 */
public class LegacyQueryUniverse implements TargetUniverse {

  private final Parser parser;
  private final PerBuildState parserState;
  private final Optional<TargetConfiguration> targetConfiguration;
  // Query execution is single threaded, however the buildTransitiveClosure implementation
  // traverses the graph in parallel.
  private final MutableDirectedGraph<BuildTarget> graph = MutableDirectedGraph.createConcurrent();
  private final Map<BuildTarget, TargetNode<?>> targetsToNodes = new ConcurrentHashMap<>();
  private final TemporaryUnconfiguredTargetToTargetUniquenessChecker checker;
  private final BuckEventBus eventBus;
  private final ExecutorService service;

  public LegacyQueryUniverse(
      Parser parser,
      PerBuildState parserState,
      Optional<TargetConfiguration> targetConfiguration,
      TemporaryUnconfiguredTargetToTargetUniquenessChecker checker,
      BuckEventBus eventBus,
      ExecutorService service) {
    this.parser = parser;
    this.parserState = parserState;
    this.targetConfiguration = targetConfiguration;
    this.checker = checker;
    this.eventBus = eventBus;
    this.service = service;
  }

  /** Creates an LegacyQueryUniverse using the parser/config from the CommandRunnerParams */
  public static LegacyQueryUniverse from(
      CommandRunnerParams params, PerBuildState parserState, ExecutorService service) {
    return new LegacyQueryUniverse(
        params.getParser(),
        parserState,
        params.getTargetConfiguration(),
        TemporaryUnconfiguredTargetToTargetUniquenessChecker.create(
            BuildBuckConfig.of(params.getCells().getRootCell().getBuckConfig())
                .shouldBuckOutIncludeTargetConfigHash()),
        params.getBuckEventBus(),
        service);
  }

  public PerBuildState getParserState() {
    return parserState;
  }

  /**
   * The target graph representing this universe.
   *
   * <p>NOTE: Due to an implementation detail (the use of {@code
   * AcyclicDepthFirstPostOrderTraversalWithPayload} when creating the graph) this is guaranteed to
   * be acyclic.
   */
  @Override
  public TraversableGraph<TargetNode<?>> getTargetGraph() {
    return new TraversableGraph<TargetNode<?>>() {
      @Override
      public Iterable<TargetNode<?>> getNodesWithNoIncomingEdges() {
        return Iterables.transform(graph.getNodesWithNoIncomingEdges(), targetsToNodes::get);
      }

      @Override
      public Iterable<TargetNode<?>> getNodesWithNoOutgoingEdges() {
        return Iterables.transform(graph.getNodesWithNoOutgoingEdges(), targetsToNodes::get);
      }

      @Override
      public Iterable<TargetNode<?>> getIncomingNodesFor(TargetNode<?> sink) {
        return Iterables.transform(
            graph.getIncomingNodesFor(sink.getBuildTarget()), targetsToNodes::get);
      }

      @Override
      public Iterable<TargetNode<?>> getOutgoingNodesFor(TargetNode<?> source) {
        return Iterables.transform(
            graph.getOutgoingNodesFor(source.getBuildTarget()), targetsToNodes::get);
      }

      @Override
      public Iterable<TargetNode<?>> getNodes() {
        return Iterables.transform(graph.getNodes(), targetsToNodes::get);
      }
    };
  }

  @Override
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      Iterable<? extends TargetNodeSpec> specs)
      throws BuildFileParseException, InterruptedException {
    return parser.resolveTargetSpecs(parserState, specs, targetConfiguration);
  }

  @Override
  public Optional<TargetNode<?>> getNode(BuildTarget buildTarget) {
    TargetNode<?> node = targetsToNodes.get(buildTarget);
    if (node != null) {
      return Optional.of(node);
    }

    // If the target doesn't exist then {@code getTargetNodeAssertCompatible}
    // will throw a {@code BuildFileParseException}, so if we get a result
    // then we know the target exists.
    return Optional.of(
        parser.getTargetNodeAssertCompatible(
            parserState, buildTarget, DependencyStack.top(buildTarget)));
  }

  @Override
  public ImmutableList<TargetNode<?>> getAllTargetNodesInBuildFile(Cell cell, AbsPath buildFile) {
    try {
      return parser.getAllTargetNodesWithTargetCompatibilityFiltering(
          parserState, cell, buildFile, targetConfiguration);
    } catch (BuildFileParseException e) {
      throw new HumanReadableException(e);
    }
  }

  @Override
  public ImmutableSet<BuildTarget> getTransitiveClosure(Collection<BuildTarget> targets) {
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(eventBus.isolated(), "legacy_query_env.get_transitive_closure")) {
      ImmutableSet.Builder<BuildTarget> result =
          ImmutableSet.builderWithExpectedSize(targets.size());
      ConsumingTraverser.breadthFirst(
              targets.stream()
                  .filter(target -> getNode(target).isPresent())
                  .collect(Collectors.toList()),
              (target, consumer) ->
                  Streams.stream(graph.getOutgoingNodesFor(target)).forEach(consumer))
          .forEach(result::add);
      return result.build();
    }
  }

  @Override
  public void buildTransitiveClosure(Set<BuildTarget> targets) throws QueryException {
    // TODO(mkosiba): This looks more and more like the Parser.buildTargetGraph method. Unify the
    // two.
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(
            eventBus.isolated(), "legacy_query_universe.build_transitive_closure")) {
      ConcurrentHashMap<BuildTarget, ListenableFuture<Unit>> jobsCache = new ConcurrentHashMap<>();
      List<ListenableFuture<Unit>> depsFuture = new ArrayList<>();
      targets.stream()
          .filter(buildTarget -> !targetsToNodes.containsKey(buildTarget))
          .forEach(
              buildTarget ->
                  discoverNewTargetsConcurrently(
                          buildTarget, DependencyStack.top(buildTarget), jobsCache)
                      .ifPresent(depsFuture::add));
      Futures.allAsList(depsFuture).get();
    } catch (ExecutionException e) {
      if (e.getCause() != null) {
        throw new QueryException(
            e.getCause(),
            "Failed parsing: " + MoreExceptions.getHumanReadableOrLocalizedMessage(e.getCause()));
      }
      propagateCauseIfInstanceOf(e, ExecutionException.class);
      propagateCauseIfInstanceOf(e, UncheckedExecutionException.class);
    } catch (BuildFileParseException | InterruptedException e) {
      throw new QueryException(
          e, "Failed parsing: " + MoreExceptions.getHumanReadableOrLocalizedMessage(e));
    }

    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(eventBus.isolated(), "legacy_query_universe.detect_cycles")) {
      if (!graph.isAcyclic()) {
        AcyclicDepthFirstPostOrderTraversal<BuildTarget> targetTraversal =
            new AcyclicDepthFirstPostOrderTraversal<>(
                target -> graph.getOutgoingNodesFor(target).iterator());
        targetTraversal.traverse(graph.getNodes());
      }
    } catch (CycleException e) {
      throw new QueryException(e, e.getMessage());
    }
  }

  @Override
  public ImmutableSet<BuildTarget> getAllTargetsFromOutgoingEdgesOf(BuildTarget target) {
    Optional<TargetNode<?>> maybeNode = getNode(target);
    if (!maybeNode.isPresent()) {
      return ImmutableSet.of();
    }

    ImmutableSet.Builder<BuildTarget> result = ImmutableSet.builder();

    for (BuildTarget parentTarget : graph.getOutgoingNodesFor(target)) {
      result.add(parentTarget);
    }

    return result.build();
  }

  @Override
  public ImmutableSet<BuildTarget> getAllTargetsFromIncomingEdgesOf(BuildTarget target) {
    Optional<TargetNode<?>> maybeNode = getNode(target);
    if (!maybeNode.isPresent()) {
      return ImmutableSet.of();
    }

    ImmutableSet.Builder<BuildTarget> result = ImmutableSet.builder();

    for (BuildTarget parentTarget : graph.getIncomingNodesFor(target)) {
      result.add(parentTarget);
    }

    return result.build();
  }

  private Optional<ListenableFuture<Unit>> discoverNewTargetsConcurrently(
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      ConcurrentHashMap<BuildTarget, ListenableFuture<Unit>> jobsCache)
      throws BuildFileParseException {
    ListenableFuture<Unit> job = jobsCache.get(buildTarget);
    if (job != null) {
      return Optional.empty();
    }
    SettableFuture<Unit> newJob = SettableFuture.create();
    if (jobsCache.putIfAbsent(buildTarget, newJob) != null) {
      return Optional.empty();
    }

    ListenableFuture<Unit> future =
        Futures.transformAsync(
            parser.getTargetNodeJobAssertCompatible(parserState, buildTarget, dependencyStack),
            targetNode -> {
              Preconditions.checkNotNull(targetNode);
              if (targetsToNodes.putIfAbsent(buildTarget, targetNode) != null) {
                return Futures.immediateFuture(Unit.UNIT);
              }
              graph.addNode(buildTarget);
              checker.addTarget(buildTarget, dependencyStack);

              // `TargetNode.getParseDeps()` is expensive (as it's layers of `Sets.union()`), so we
              // avoid it when possible and parallelize it here via the executor.
              ImmutableList<BuildTarget> parseDeps =
                  ImmutableList.copyOf(targetNode.getParseDeps());
              graph.setEdges(buildTarget, parseDeps);

              List<ListenableFuture<Unit>> depsFutures = new ArrayList<>();
              for (BuildTarget parseDep : parseDeps) {
                discoverNewTargetsConcurrently(parseDep, dependencyStack.child(parseDep), jobsCache)
                    .ifPresent(
                        parseDepJob ->
                            depsFutures.add(
                                attachParentNodeToErrorMessage(
                                    buildTarget, parseDep, parseDepJob)));
              }
              if (depsFutures.isEmpty()) {
                return Futures.immediateFuture(Unit.UNIT);
              }
              return Futures.transform(
                  Futures.allAsList(depsFutures),
                  unused -> Unit.UNIT,
                  MoreExecutors.directExecutor());
            },
            service);
    Preconditions.checkState(newJob.setFuture(future));
    return Optional.of(newJob);
  }

  private static ListenableFuture<Unit> attachParentNodeToErrorMessage(
      BuildTarget buildTarget, BuildTarget parseDep, ListenableFuture<Unit> depWork) {
    return Futures.catchingAsync(
        depWork,
        Exception.class,
        exceptionInput -> {
          if (exceptionInput instanceof BuildFileParseException) {
            if (exceptionInput instanceof BuildTargetException) {
              throw ParserMessages.createReadableExceptionWithWhenSuffix(
                  buildTarget, parseDep, (BuildTargetException) exceptionInput);
            } else {
              throw ParserMessages.createReadableExceptionWithWhenSuffix(
                  buildTarget, parseDep, (BuildFileParseException) exceptionInput);
            }
          }
          throw exceptionInput;
        });
  }
}
