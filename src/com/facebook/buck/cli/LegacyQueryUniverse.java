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
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversalWithPayload;
import com.facebook.buck.core.util.graph.CycleException;
import com.facebook.buck.core.util.graph.GraphTraversableWithPayload;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.graph.TraversableGraph;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserMessages;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.parser.temporarytargetuniquenesschecker.TemporaryUnconfiguredTargetToTargetUniquenessChecker;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * A `TargetUniverse` implementation that parses new files as necessary. This is the target universe
 * used unconditionally by legacy `buck query`, which will be replaced by `cquery` and `uquery`.
 */
public class LegacyQueryUniverse implements TargetUniverse {

  private final Parser parser;
  private final PerBuildState parserState;
  // Query execution is single threaded, however the buildTransitiveClosure implementation
  // traverses the graph in parallel.
  private MutableDirectedGraph<TargetNode<?>> graph = MutableDirectedGraph.createConcurrent();
  private Map<BuildTarget, TargetNode<?>> targetsToNodes = new ConcurrentHashMap<>();
  private TemporaryUnconfiguredTargetToTargetUniquenessChecker checker;

  public LegacyQueryUniverse(
      Parser parser,
      PerBuildState parserState,
      TemporaryUnconfiguredTargetToTargetUniquenessChecker checker) {
    this.parser = parser;
    this.parserState = parserState;
    this.checker = checker;
  }

  /** Creates an LegacyQueryUniverse using the parser/config from the CommandRunnerParams */
  public static LegacyQueryUniverse from(CommandRunnerParams params, PerBuildState parserState) {
    return new LegacyQueryUniverse(
        params.getParser(),
        parserState,
        TemporaryUnconfiguredTargetToTargetUniquenessChecker.create(
            BuildBuckConfig.of(params.getCells().getRootCell().getBuckConfig())
                .shouldBuckOutIncludeTargetConfigHash()));
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
    return graph;
  }

  @Override
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      Iterable<? extends TargetNodeSpec> specs, Optional<TargetConfiguration> targetConfiguration)
      throws BuildFileParseException, InterruptedException {
    return parser.resolveTargetSpecs(parserState, specs, targetConfiguration);
  }

  @Override
  public Optional<TargetNode<?>> getNode(BuildTarget buildTarget) {
    TargetNode<?> node = targetsToNodes.get(buildTarget);
    if (node != null) {
      return Optional.of(node);
    }

    // TODO(srice): `getTargetNodeAssertCompatible` appears to return a nonnull value, but it's hard
    // to be sure. We have little to lose by using `ofNullable`, but it may not be necessary.
    return Optional.ofNullable(
        parser.getTargetNodeAssertCompatible(
            parserState, buildTarget, DependencyStack.top(buildTarget)));
  }

  @Override
  public ImmutableList<TargetNode<?>> getAllTargetNodesWithTargetCompatibilityFiltering(
      Cell cell, AbsPath buildFile, Optional<TargetConfiguration> targetConfiguration) {
    try {
      return parser.getAllTargetNodesWithTargetCompatibilityFiltering(
          parserState, cell, buildFile, targetConfiguration);
    } catch (BuildFileParseException e) {
      throw new HumanReadableException(e);
    }
  }

  @Override
  public ImmutableSet<BuildTarget> getTransitiveClosure(Set<BuildTarget> targets) {
    Set<TargetNode<?>> nodes = new LinkedHashSet<>(targets.size());
    for (BuildTarget target : targets) {
      getNode(target).ifPresent(nodes::add);
    }

    ImmutableSet.Builder<BuildTarget> result = ImmutableSet.builder();

    new AbstractBreadthFirstTraversal<TargetNode<?>>(nodes) {
      @Override
      public Iterable<TargetNode<?>> visit(TargetNode<?> node) {
        result.add(node.getBuildTarget());
        return node.getParseDeps().stream()
            .map(targetsToNodes::get)
            .collect(ImmutableSet.toImmutableSet());
      }
    }.start();

    return result.build();
  }

  @Override
  public void buildTransitiveClosure(Set<BuildTarget> targets) throws QueryException {
    ImmutableSet<BuildTarget> newBuildTargets =
        targets.stream()
            .filter(buildTarget -> !targetsToNodes.containsKey(buildTarget))
            .collect(ImmutableSet.toImmutableSet());

    // TODO(mkosiba): This looks more and more like the Parser.buildTargetGraph method. Unify the
    // two.

    ConcurrentHashMap<BuildTarget, ListenableFuture<Unit>> jobsCache = new ConcurrentHashMap<>();

    try {
      List<ListenableFuture<Unit>> depsFuture = new ArrayList<>();
      for (BuildTarget buildTarget : newBuildTargets) {
        discoverNewTargetsConcurrently(buildTarget, DependencyStack.top(buildTarget), jobsCache)
            .ifPresent(dep -> depsFuture.add(dep));
      }
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

    GraphTraversableWithPayload<BuildTarget, TargetNode<?>> traversable =
        target -> {
          TargetNode<?> node =
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
            return new Pair<>(node, ImmutableSet.<BuildTarget>of().iterator());
          }
          return new Pair<>(node, node.getParseDeps().iterator());
        };

    AcyclicDepthFirstPostOrderTraversalWithPayload<BuildTarget, TargetNode<?>> targetNodeTraversal =
        new AcyclicDepthFirstPostOrderTraversalWithPayload<>(traversable);
    try {
      for (Pair<BuildTarget, TargetNode<?>> entry : targetNodeTraversal.traverse(newBuildTargets)) {
        TargetNode<?> node = entry.getSecond();
        graph.addNode(node);
        for (BuildTarget dep : node.getParseDeps()) {
          graph.addEdge(
              node,
              Preconditions.checkNotNull(
                  targetsToNodes.get(dep), "Couldn't find TargetNode for %s", dep));
        }
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

    TargetNode<?> node = maybeNode.get();
    ImmutableSet.Builder<BuildTarget> result = ImmutableSet.builder();

    for (TargetNode<?> parentNode : graph.getOutgoingNodesFor(node)) {
      result.add(parentNode.getBuildTarget());
    }

    return result.build();
  }

  @Override
  public ImmutableSet<BuildTarget> getAllTargetsFromIncomingEdgesOf(BuildTarget target) {
    Optional<TargetNode<?>> maybeNode = getNode(target);
    if (!maybeNode.isPresent()) {
      return ImmutableSet.of();
    }

    TargetNode<?> node = maybeNode.get();
    ImmutableSet.Builder<BuildTarget> result = ImmutableSet.builder();

    for (TargetNode<?> parentNode : graph.getIncomingNodesFor(node)) {
      result.add(parentNode.getBuildTarget());
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
              targetsToNodes.put(buildTarget, targetNode);
              checker.addTarget(buildTarget, dependencyStack);
              List<ListenableFuture<Unit>> depsFuture = new ArrayList<>();
              Set<BuildTarget> parseDeps = targetNode.getParseDeps();
              for (BuildTarget parseDep : parseDeps) {
                discoverNewTargetsConcurrently(parseDep, dependencyStack.child(parseDep), jobsCache)
                    .ifPresent(
                        depWork ->
                            depsFuture.add(
                                attachParentNodeToErrorMessage(buildTarget, parseDep, depWork)));
              }
              return Futures.transform(
                  Futures.allAsList(depsFuture),
                  Functions.constant(null),
                  MoreExecutors.directExecutor());
            });
    newJob.setFuture(future);
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
