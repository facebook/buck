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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.attr.ImplicitFlavorsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.HasDefaultFlavors;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.ImmutableBuildTarget;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.MoreMaps;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * High-level build file parsing machinery. Primarily responsible for producing a {@link
 * TargetGraph} based on a set of targets. Caches build rules to minimise the number of calls to
 * python and processes filesystem WatchEvents to invalidate the cache as files change.
 */
public class DefaultParser implements Parser {

  private static final Logger LOG = Logger.get(Parser.class);

  private final PerBuildStateFactory perBuildStateFactory;
  private final DaemonicParserState permState;
  private final ConstructorArgMarshaller marshaller;
  private final TypeCoercerFactory typeCoercerFactory;
  private final KnownBuildRuleTypesProvider knownBuildRuleTypesProvider;
  private final ParserPythonInterpreterProvider parserPythonInterpreterProvider;
  private final TargetSpecResolver targetSpecResolver;

  public DefaultParser(
      ParserConfig parserConfig,
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      ExecutableFinder executableFinder,
      TargetSpecResolver targetSpecResolver) {
    this.perBuildStateFactory = new PerBuildStateFactory();
    this.typeCoercerFactory = typeCoercerFactory;
    this.permState =
        new DaemonicParserState(
            typeCoercerFactory,
            parserConfig.getNumParsingThreads(),
            parserConfig.shouldIgnoreEnvironmentVariablesChanges());
    this.marshaller = marshaller;
    this.knownBuildRuleTypesProvider = knownBuildRuleTypesProvider;
    this.parserPythonInterpreterProvider =
        new ParserPythonInterpreterProvider(parserConfig, executableFinder);
    this.targetSpecResolver = targetSpecResolver;
  }

  @Override
  public DaemonicParserState getPermState() {
    return permState;
  }

  @VisibleForTesting
  static ImmutableSet<Map<String, Object>> getTargetNodeRawAttributes(
      PerBuildState state, Cell cell, Path buildFile) throws BuildFileParseException {
    Preconditions.checkState(buildFile.isAbsolute());
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));
    return state.getAllRawNodes(cell, buildFile);
  }

  @Override
  public ImmutableSet<TargetNode<?, ?>> getAllTargetNodes(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Path buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(
        buildFile.isAbsolute(),
        "Build file should be referred to using an absolute path: %s",
        buildFile);
    Preconditions.checkState(
        buildFile.startsWith(cell.getRoot()),
        "Roots do not match %s -> %s",
        cell.getRoot(),
        buildFile);

    try (PerBuildState state =
        perBuildStateFactory.create(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            cell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            SpeculativeParsing.ENABLED)) {
      return state.getAllTargetNodes(cell, buildFile);
    }
  }

  @Override
  public TargetNode<?, ?> getTargetNode(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      BuildTarget target)
      throws BuildFileParseException {
    try (PerBuildState state =
        perBuildStateFactory.create(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            cell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            SpeculativeParsing.DISABLED)) {
      return state.getTargetNode(target);
    }
  }

  @Override
  public TargetNode<?, ?> getTargetNode(PerBuildState perBuildState, BuildTarget target)
      throws BuildFileParseException {
    return perBuildState.getTargetNode(target);
  }

  @Override
  public ListenableFuture<TargetNode<?, ?>> getTargetNodeJob(
      PerBuildState perBuildState, BuildTarget target) throws BuildTargetException {
    return perBuildState.getTargetNodeJob(target);
  }

  @Nullable
  @Override
  public SortedMap<String, Object> getTargetNodeRawAttributes(
      PerBuildState state, Cell cell, TargetNode<?, ?> targetNode) throws BuildFileParseException {
    try {

      Cell owningCell = cell.getCell(targetNode.getBuildTarget());
      ImmutableSet<Map<String, Object>> allRawNodes =
          getTargetNodeRawAttributes(
              state, owningCell, cell.getAbsolutePathToBuildFile(targetNode.getBuildTarget()));

      String shortName = targetNode.getBuildTarget().getShortName();
      for (Map<String, Object> rawNode : allRawNodes) {
        if (shortName.equals(rawNode.get("name"))) {
          SortedMap<String, Object> toReturn = new TreeMap<>();
          toReturn.putAll(rawNode);
          toReturn.put(
              "buck.direct_dependencies",
              targetNode
                  .getParseDeps()
                  .stream()
                  .map(Object::toString)
                  .collect(ImmutableList.toImmutableList()));
          return toReturn;
        }
      }
    } catch (MissingBuildFileException e) {
      throw new RuntimeException("Deeply unlikely to be true: the cell is missing: " + targetNode);
    }
    return null;
  }

  /**
   * @deprecated Prefer {@link #getTargetNodeRawAttributes(PerBuildState, Cell, TargetNode)} and
   *     reusing a PerBuildState instance, especially when calling in a loop.
   */
  @Nullable
  @Deprecated
  @Override
  public SortedMap<String, Object> getTargetNodeRawAttributes(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      TargetNode<?, ?> targetNode)
      throws BuildFileParseException {

    try (PerBuildState state =
        perBuildStateFactory.create(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            cell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            SpeculativeParsing.DISABLED)) {
      return getTargetNodeRawAttributes(state, cell, targetNode);
    }
  }

  private RuntimeException propagateRuntimeCause(RuntimeException e)
      throws IOException, InterruptedException, BuildFileParseException {
    Throwables.throwIfInstanceOf(e, HumanReadableException.class);

    Throwable t = e.getCause();
    if (t != null) {
      Throwables.throwIfInstanceOf(t, IOException.class);
      Throwables.throwIfInstanceOf(t, InterruptedException.class);
      Throwables.throwIfInstanceOf(t, BuildFileParseException.class);
      Throwables.throwIfInstanceOf(t, BuildTargetException.class);
    }
    return e;
  }

  @Override
  public TargetGraph buildTargetGraph(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<BuildTarget> toExplore)
      throws IOException, InterruptedException, BuildFileParseException {
    if (Iterables.isEmpty(toExplore)) {
      return TargetGraph.EMPTY;
    }

    try (PerBuildState state =
        perBuildStateFactory.create(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            rootCell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            SpeculativeParsing.ENABLED)) {
      return buildTargetGraph(state, eventBus, toExplore);
    }
  }

  private TargetGraph buildTargetGraph(
      PerBuildState state, BuckEventBus eventBus, Iterable<BuildTarget> toExplore)
      throws IOException, InterruptedException, BuildFileParseException {

    if (Iterables.isEmpty(toExplore)) {
      return TargetGraph.EMPTY;
    }

    MutableDirectedGraph<TargetNode<?, ?>> graph = new MutableDirectedGraph<>();
    Map<BuildTarget, TargetNode<?, ?>> index = new HashMap<>();

    ParseEvent.Started parseStart = ParseEvent.started(toExplore);
    eventBus.post(parseStart);

    GraphTraversable<BuildTarget> traversable =
        target -> {
          TargetNode<?, ?> node;
          try {
            node = state.getTargetNode(target);
          } catch (BuildFileParseException e) {
            throw new RuntimeException(e);
          }

          // this second lookup loop may *seem* pointless, but it allows us to report which node is
          // referring to a node we can't find - something that's very difficult in this Traversable
          // visitor pattern otherwise.
          // it's also work we need to do anyways. the getTargetNode() result is cached, so that
          // when we come around and re-visit that node there won't actually be any work performed.
          for (BuildTarget dep : node.getParseDeps()) {
            try {
              state.getTargetNode(dep);
            } catch (BuildFileParseException e) {
              throw ParserMessages.createReadableExceptionWithWhenSuffix(target, dep, e);
            } catch (HumanReadableException e) {
              throw ParserMessages.createReadableExceptionWithWhenSuffix(target, dep, e);
            }
          }
          return node.getParseDeps().iterator();
        };

    AcyclicDepthFirstPostOrderTraversal<BuildTarget> targetNodeTraversal =
        new AcyclicDepthFirstPostOrderTraversal<>(traversable);

    TargetGraph targetGraph = null;
    try {
      for (BuildTarget target : targetNodeTraversal.traverse(toExplore)) {
        TargetNode<?, ?> targetNode = state.getTargetNode(target);

        Preconditions.checkNotNull(targetNode, "No target node found for %s", target);
        graph.addNode(targetNode);
        MoreMaps.putCheckEquals(index, target, targetNode);
        if (target.isFlavored()) {
          BuildTarget unflavoredTarget = ImmutableBuildTarget.of(target.getUnflavoredBuildTarget());
          MoreMaps.putCheckEquals(index, unflavoredTarget, state.getTargetNode(unflavoredTarget));
        }
        for (BuildTarget dep : targetNode.getParseDeps()) {
          graph.addEdge(targetNode, state.getTargetNode(dep));
        }
      }

      targetGraph = new TargetGraph(graph, ImmutableMap.copyOf(index));
      return targetGraph;
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new HumanReadableException(e.getMessage());
    } catch (RuntimeException e) {
      throw propagateRuntimeCause(e);
    } finally {
      eventBus.post(
          ParseEvent.finished(
              parseStart, state.getParseProcessedBytes(), Optional.ofNullable(targetGraph)));
    }
  }

  /**
   * @param eventBus used to log events while parsing.
   * @param targetNodeSpecs the specs representing the build targets to generate a target graph for.
   * @return the target graph containing the build targets and their related targets.
   */
  @Override
  public synchronized TargetGraphAndBuildTargets buildTargetGraphForTargetNodeSpecs(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs)
      throws BuildFileParseException, IOException, InterruptedException {
    return buildTargetGraphForTargetNodeSpecs(
        eventBus,
        rootCell,
        enableProfiling,
        executor,
        targetNodeSpecs,
        ParserConfig.ApplyDefaultFlavorsMode.DISABLED);
  }

  /**
   * @param eventBus used to log events while parsing.
   * @param targetNodeSpecs the specs representing the build targets to generate a target graph for.
   * @return the target graph containing the build targets and their related targets.
   */
  @Override
  public synchronized TargetGraphAndBuildTargets buildTargetGraphForTargetNodeSpecs(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, IOException, InterruptedException {

    try (PerBuildState state =
        perBuildStateFactory.create(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            rootCell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            SpeculativeParsing.ENABLED)) {

      ImmutableSet<BuildTarget> buildTargets =
          ImmutableSet.copyOf(
              Iterables.concat(
                  targetSpecResolver.resolveTargetSpecs(
                      eventBus,
                      rootCell,
                      targetNodeSpecs,
                      (buildTarget, targetNode, targetType) ->
                          applyDefaultFlavors(
                              buildTarget, targetNode, targetType, applyDefaultFlavorsMode),
                      state.getTargetNodeProviderForSpecResolver(),
                      (spec, nodes) -> spec.filter(nodes))));
      TargetGraph graph = buildTargetGraph(state, eventBus, buildTargets);

      return TargetGraphAndBuildTargets.builder()
          .setBuildTargets(buildTargets)
          .setTargetGraph(graph)
          .build();
    }
  }

  @Override
  public String toString() {
    return permState.toString();
  }

  @Override
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> specs,
      SpeculativeParsing speculativeParsing,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, InterruptedException, IOException {

    try (PerBuildState state =
        perBuildStateFactory.create(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            rootCell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            speculativeParsing)) {
      return targetSpecResolver.resolveTargetSpecs(
          eventBus,
          rootCell,
          specs,
          (buildTarget, targetNode, targetType) ->
              applyDefaultFlavors(buildTarget, targetNode, targetType, applyDefaultFlavorsMode),
          state.getTargetNodeProviderForSpecResolver(),
          (spec, nodes) -> spec.filter(nodes));
    }
  }

  @VisibleForTesting
  static BuildTarget applyDefaultFlavors(
      BuildTarget target,
      Optional<TargetNode<?, ?>> targetNode,
      TargetNodeSpec.TargetType targetType,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode) {
    if (target.isFlavored()
        || !targetNode.isPresent()
        || targetType == TargetNodeSpec.TargetType.MULTIPLE_TARGETS
        || applyDefaultFlavorsMode == ParserConfig.ApplyDefaultFlavorsMode.DISABLED) {
      return target;
    }

    TargetNode<?, ?> node = targetNode.get();

    ImmutableSortedSet<Flavor> defaultFlavors = ImmutableSortedSet.of();
    if (node.getConstructorArg() instanceof HasDefaultFlavors) {
      defaultFlavors = ((HasDefaultFlavors) node.getConstructorArg()).getDefaultFlavors();
      LOG.debug("Got default flavors %s from args of %s", defaultFlavors, target);
    }

    if (node.getDescription() instanceof ImplicitFlavorsInferringDescription) {
      defaultFlavors =
          ((ImplicitFlavorsInferringDescription) node.getDescription())
              .addImplicitFlavors(defaultFlavors);
      LOG.debug("Got default flavors %s from description of %s", defaultFlavors, target);
    }

    return target.withFlavors(defaultFlavors);
  }

  @Override
  public void register(EventBus eventBus) {
    eventBus.register(permState);
  }
}
