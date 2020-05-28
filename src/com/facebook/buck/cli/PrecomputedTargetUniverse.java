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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversalWithPayload;
import com.facebook.buck.core.util.graph.CycleException;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.core.util.graph.GraphTraversableWithPayload;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserMessages;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.spec.BuildFileSpec;
import com.facebook.buck.parser.spec.BuildTargetMatcherTargetNodeParser;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** A TargetUniverse implementation that operates on an already-made graph. */
public class PrecomputedTargetUniverse implements TargetUniverse {

  private static final Logger LOG = Logger.get(PrecomputedTargetUniverse.class);

  private final DirectedAcyclicGraph<TargetNode<?>> graph;
  private final ImmutableMap<BuildTarget, TargetNode<?>> targetToNodeIndex;
  private final ImmutableSetMultimap<CellRelativePath, BuildTarget> pathToBuildTargetIndex;
  private final BuildFileDescendantsIndex descendantsIndex;

  /** Creates a `PrecomputedTargetUniverse` by parsing the transitive closure of `targets`. */
  public static PrecomputedTargetUniverse createFromRootTargets(
      List<String> targets, CommandRunnerParams params, PerBuildState perBuildState)
      throws QueryException {

    LOG.debug("Creating universe from %d roots", targets.size());

    MutableDirectedGraph<TargetNode<?>> graph = MutableDirectedGraph.createConcurrent();
    // Ideally we would use an `ImmutableMap.Builder` here, but at various points we assert that a
    // node is already in the map and `ImmutableMap.Builder` has no `get` method.
    Map<BuildTarget, TargetNode<?>> targetToNodeIndex = new ConcurrentHashMap<>();

    Cell rootCell = params.getCells().getRootCell();
    Parser parser = params.getParser();
    CommandLineTargetNodeSpecParser specParser =
        new CommandLineTargetNodeSpecParser(
            rootCell,
            params.getClientWorkingDir(),
            params.getBuckConfig(),
            new BuildTargetMatcherTargetNodeParser());
    ImmutableSet<TargetNodeSpec> universeSpecs =
        targets.stream()
            .flatMap(arg -> specParser.parse(rootCell, arg).stream())
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSet<BuildTarget> rootTargets;
    try {
      rootTargets =
          parser
              .resolveTargetSpecs(
                  perBuildState.getParsingContext(), universeSpecs, params.getTargetConfiguration())
              .stream()
              .flatMap(ImmutableSet::stream)
              .collect(ImmutableSet.toImmutableSet());
    } catch (InterruptedException e) {
      throw new QueryException(
          e.getCause(),
          "Failed parsing: " + MoreExceptions.getHumanReadableOrLocalizedMessage(e.getCause()));
    }

    // Most of this is taken from the `buildTransitiveClosure` implementation of
    // `LegacyQueryUniverse`. We are tracking more information (and with different types) than that
    // class though which makes sharing code hard.

    ConcurrentHashMap<BuildTarget, ListenableFuture<Unit>> jobsCache = new ConcurrentHashMap<>();

    try {
      List<ListenableFuture<Unit>> depsFuture = new ArrayList<>();
      for (BuildTarget buildTarget : rootTargets) {
        discoverNewTargetsConcurrently(
                targetToNodeIndex,
                parser,
                perBuildState,
                buildTarget,
                DependencyStack.top(buildTarget),
                jobsCache)
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
              Objects.requireNonNull(
                  targetToNodeIndex.get(target),
                  () ->
                      String.format(
                          "Node %s should have been discovered by `discoverNewTargetsConcurrently`.",
                          target));

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
    ImmutableSetMultimap.Builder<CellRelativePath, BuildTarget> pathToBuildTargetIndexBuilder =
        ImmutableSetMultimap.builder();
    try {
      for (Pair<BuildTarget, TargetNode<?>> entry : targetNodeTraversal.traverse(rootTargets)) {
        TargetNode<?> node = entry.getSecond();
        graph.addNode(node);

        BuildTarget buildTarget = node.getBuildTarget();
        pathToBuildTargetIndexBuilder.put(buildTarget.getCellRelativeBasePath(), buildTarget);

        for (BuildTarget dep : node.getParseDeps()) {
          graph.addEdge(
              node,
              Objects.requireNonNull(
                  targetToNodeIndex.get(dep),
                  () -> String.format("Couldn't find TargetNode for %s", dep)));
        }
      }
    } catch (CycleException e) {
      throw new QueryException(e, e.getMessage());
    }

    ImmutableSetMultimap<CellRelativePath, BuildTarget> pathToBuildTargetIndex =
        pathToBuildTargetIndexBuilder.build();

    LOG.debug("Finished creating universe with final total of %d nodes", graph.getNodeCount());

    return new PrecomputedTargetUniverse(
        new DirectedAcyclicGraph<>(graph),
        ImmutableMap.copyOf(targetToNodeIndex),
        pathToBuildTargetIndex,
        BuildFileDescendantsIndex.createFromLeafPaths(pathToBuildTargetIndex.keySet()));
  }

  private PrecomputedTargetUniverse(
      DirectedAcyclicGraph<TargetNode<?>> graph,
      ImmutableMap<BuildTarget, TargetNode<?>> targetToNodeIndex,
      ImmutableSetMultimap<CellRelativePath, BuildTarget> pathToBuildTargetIndex,
      BuildFileDescendantsIndex descendantsIndex) {
    this.graph = graph;
    this.targetToNodeIndex = targetToNodeIndex;
    this.pathToBuildTargetIndex = pathToBuildTargetIndex;
    this.descendantsIndex = descendantsIndex;
  }

  @Override
  public DirectedAcyclicGraph<TargetNode<?>> getTargetGraph() {
    return graph;
  }

  @Override
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      Iterable<? extends TargetNodeSpec> specs,
      Optional<TargetConfiguration> targetConfiguration,
      ParsingContext parsingContext)
      throws BuildFileParseException, InterruptedException {
    ImmutableList.Builder<ImmutableSet<BuildTarget>> resultBuilder = ImmutableList.builder();
    for (TargetNodeSpec spec : specs) {
      ImmutableSet<CellRelativePath> specBuildFiles = resolveBuildFilePathsForSpec(spec);
      ImmutableSet<TargetNodeMaybeIncompatible> buildfileTargetNodes =
          specBuildFiles.stream()
              .flatMap(path -> pathToBuildTargetIndex.get(path).stream())
              .map(targetToNodeIndex::get)
              .map(TargetNodeMaybeIncompatible::ofCompatible)
              .collect(ImmutableSet.toImmutableSet());
      resultBuilder.add(spec.filter(buildfileTargetNodes).keySet());
    }
    return resultBuilder.build();
  }

  @Override
  public Optional<TargetNode<?>> getNode(BuildTarget buildTarget) {
    return Optional.ofNullable(targetToNodeIndex.get(buildTarget));
  }

  @Override
  public ImmutableList<TargetNode<?>> getAllTargetNodesWithTargetCompatibilityFiltering(
      Cell cell, AbsPath buildFile, Optional<TargetConfiguration> targetConfiguration) {
    // TODO(srice): Should we be ignorning the configuration here?

    // The index was built up with directories, but the input to this function is a buildfile path.
    // Make sure we're getting what we expect and then chop off the buildfile name.
    Preconditions.checkState(
        buildFile.endsWith(cell.getBuckConfigView(ParserConfig.class).getBuildFileName()));
    AbsPath buildFileDirectory = buildFile.getParent();
    CellRelativePath cellRelativePath =
        CellRelativePath.of(
            cell.getCanonicalName(),
            ForwardRelativePath.ofRelPath(cell.getRoot().relativize(buildFileDirectory)));
    return pathToBuildTargetIndex.get(cellRelativePath).stream()
        .map(targetToNodeIndex::get)
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public ImmutableSet<BuildTarget> getTransitiveClosure(Set<BuildTarget> targets)
      throws QueryException {
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
            .map(targetToNodeIndex::get)
            .collect(ImmutableSet.toImmutableSet());
      }
    }.start();

    return result.build();
  }

  @Override
  public void buildTransitiveClosure(Set<BuildTarget> targets) throws QueryException {
    // This method explicitly does nothing.
    // In other TargetUniverse implementations this would be used to expand the graph for functions
    // that need it (aka making sure we have already parsed `//foo:bar` if you run
    // `deps(//foo:bar)`.
    // With a PrecomputedTargetUniverse we don't want to expand the graph, we want to resolve the
    // query off of the exact graph that was precomputed. Therefore, do nothing.
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

  private static Optional<ListenableFuture<Unit>> discoverNewTargetsConcurrently(
      Map<BuildTarget, TargetNode<?>> targetToNodeIndex,
      Parser parser,
      PerBuildState parserState,
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
              targetToNodeIndex.put(buildTarget, targetNode);
              List<ListenableFuture<Unit>> depsFuture = new ArrayList<>();
              Set<BuildTarget> parseDeps = targetNode.getParseDeps();
              for (BuildTarget parseDep : parseDeps) {
                discoverNewTargetsConcurrently(
                        targetToNodeIndex,
                        parser,
                        parserState,
                        parseDep,
                        dependencyStack.child(parseDep),
                        jobsCache)
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

  private ImmutableSet<CellRelativePath> resolveBuildFilePathsForSpec(TargetNodeSpec spec) {
    BuildFileSpec buildFileSpec = spec.getBuildFileSpec();
    CellRelativePath buildFilePath = buildFileSpec.getCellRelativeBaseName();
    if (!buildFileSpec.isRecursive()) {
      return ImmutableSet.of(buildFilePath);
    } else {
      return descendantsIndex.getRecursiveDescendants(buildFilePath);
    }
  }
}
