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
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.core.util.graph.GraphTraversable;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.AbstractParserConfig.ApplyDefaultFlavorsMode;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeProviderForSpecResolver;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.util.MoreMaps;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Evaluates build files using one of the supported interpreters and provides information about
 * build targets defined in them.
 *
 * <p>Computed targets are cached but are automatically invalidated if Watchman reports any
 * filesystem changes that may affect computed results.
 */
class DefaultParser implements Parser {

  private static final Logger LOG = Logger.get(Parser.class);

  protected final PerBuildStateFactory perBuildStateFactory;
  protected final DaemonicParserState permState;
  protected final TargetSpecResolver targetSpecResolver;
  protected final Watchman watchman;
  protected final BuckEventBus eventBus;
  protected final Supplier<ImmutableList<String>> targetPlatforms;

  DefaultParser(
      DaemonicParserState daemonicParserState,
      PerBuildStateFactory perBuildStateFactory,
      TargetSpecResolver targetSpecResolver,
      Watchman watchman,
      BuckEventBus eventBus,
      Supplier<ImmutableList<String>> targetPlatforms) {
    this.perBuildStateFactory = perBuildStateFactory;
    this.watchman = watchman;
    this.permState = daemonicParserState;
    this.targetSpecResolver = targetSpecResolver;
    this.eventBus = eventBus;
    this.targetPlatforms = targetPlatforms;
  }

  @Override
  public DaemonicParserState getPermState() {
    return permState;
  }

  @Override
  public PerBuildStateFactory getPerBuildStateFactory() {
    return perBuildStateFactory;
  }

  @VisibleForTesting
  static BuildFileManifest getTargetNodeRawAttributes(
      PerBuildState state, Cell cell, Path buildFile) throws BuildFileParseException {
    Preconditions.checkState(buildFile.isAbsolute());
    return state.getBuildFileManifest(cell, buildFile);
  }

  @Override
  public ImmutableList<TargetNode<?>> getAllTargetNodes(
      PerBuildState perBuildState, Cell cell, Path buildFile) throws BuildFileParseException {
    return perBuildState.getAllTargetNodes(cell, buildFile);
  }

  @Override
  public TargetNode<?> getTargetNode(
      Cell cell, boolean enableProfiling, ListeningExecutorService executor, BuildTarget target)
      throws BuildFileParseException {
    try (PerBuildState state =
        perBuildStateFactory.create(
            permState,
            executor,
            cell,
            targetPlatforms.get(),
            enableProfiling,
            SpeculativeParsing.DISABLED)) {
      return state.getTargetNode(target);
    }
  }

  @Override
  public TargetNode<?> getTargetNode(PerBuildState perBuildState, BuildTarget target)
      throws BuildFileParseException {
    return perBuildState.getTargetNode(target);
  }

  @Override
  public ListenableFuture<TargetNode<?>> getTargetNodeJob(
      PerBuildState perBuildState, BuildTarget target) throws BuildTargetException {
    return perBuildState.getTargetNodeJob(target);
  }

  @Nullable
  @Override
  public SortedMap<String, Object> getTargetNodeRawAttributes(
      PerBuildState state, Cell cell, TargetNode<?> targetNode) throws BuildFileParseException {
    Cell owningCell = cell.getCell(targetNode.getBuildTarget());
    BuildFileManifest buildFileManifest =
        getTargetNodeRawAttributes(
            state, owningCell, cell.getAbsolutePathToBuildFile(targetNode.getBuildTarget()));
    String shortName = targetNode.getBuildTarget().getShortName();

    if (!buildFileManifest.getTargets().containsKey(shortName)) {
      return null;
    }

    SortedMap<String, Object> attributes =
        new TreeMap<>(buildFileManifest.getTargets().get(shortName));
    attributes.put(
        InternalTargetAttributeNames.DIRECT_DEPENDENCIES,
        targetNode
            .getParseDeps()
            .stream()
            .map(Object::toString)
            .collect(ImmutableList.toImmutableList()));
    return attributes;
  }

  /**
   * @deprecated Prefer {@link #getTargetNodeRawAttributes(PerBuildState, Cell, TargetNode)} and
   *     reusing a PerBuildState instance, especially when calling in a loop.
   */
  @Nullable
  @Deprecated
  @Override
  public SortedMap<String, Object> getTargetNodeRawAttributes(
      Cell cell, ListeningExecutorService executor, TargetNode<?> targetNode)
      throws BuildFileParseException {

    try (PerBuildState state =
        perBuildStateFactory.create(
            permState, executor, cell, targetPlatforms.get(), false, SpeculativeParsing.DISABLED)) {
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
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<BuildTarget> toExplore)
      throws IOException, InterruptedException, BuildFileParseException {
    if (Iterables.isEmpty(toExplore)) {
      return TargetGraph.EMPTY;
    }

    AtomicLong processedBytes = new AtomicLong();
    try (PerBuildState state =
        perBuildStateFactory.create(
            permState,
            executor,
            rootCell,
            targetPlatforms.get(),
            enableProfiling,
            processedBytes,
            SpeculativeParsing.ENABLED)) {
      return buildTargetGraph(state, toExplore, processedBytes);
    }
  }

  private TargetGraph buildTargetGraph(
      PerBuildState state, Iterable<BuildTarget> toExplore, AtomicLong processedBytes)
      throws IOException, InterruptedException, BuildFileParseException {

    if (Iterables.isEmpty(toExplore)) {
      return TargetGraph.EMPTY;
    }

    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    Map<BuildTarget, TargetNode<?>> index = new HashMap<>();

    ParseEvent.Started parseStart = ParseEvent.started(toExplore);
    eventBus.post(parseStart);

    GraphTraversable<BuildTarget> traversable =
        target -> {
          TargetNode<?> node;
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
        TargetNode<?> targetNode = state.getTargetNode(target);

        Preconditions.checkNotNull(targetNode, "No target node found for %s", target);
        graph.addNode(targetNode);
        MoreMaps.putCheckEquals(index, target, targetNode);
        if (target.isFlavored()) {
          BuildTarget unflavoredTarget = target.withoutFlavors();
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
          ParseEvent.finished(parseStart, processedBytes.get(), Optional.ofNullable(targetGraph)));
    }
  }

  /**
   * @param targetNodeSpecs the specs representing the build targets to generate a target graph for.
   * @return the target graph containing the build targets and their related targets.
   */
  @Override
  public synchronized TargetGraphAndBuildTargets buildTargetGraphForTargetNodeSpecs(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs)
      throws BuildFileParseException, IOException, InterruptedException {
    return buildTargetGraphForTargetNodeSpecs(
        rootCell,
        enableProfiling,
        executor,
        targetNodeSpecs,
        false,
        false,
        ParserConfig.ApplyDefaultFlavorsMode.DISABLED);
  }

  @Override
  public synchronized TargetGraphAndBuildTargets buildTargetGraphWithoutConfigurationTargets(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      boolean excludeUnsupportedTargets,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, IOException, InterruptedException {
    return buildTargetGraphForTargetNodeSpecs(
        rootCell,
        enableProfiling,
        executor,
        targetNodeSpecs,
        excludeUnsupportedTargets,
        true,
        applyDefaultFlavorsMode);
  }

  @Override
  public synchronized TargetGraphAndBuildTargets buildTargetGraphWithConfigurationTargets(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      boolean excludeUnsupportedTargets,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, IOException, InterruptedException {
    return buildTargetGraphForTargetNodeSpecs(
        rootCell,
        enableProfiling,
        executor,
        targetNodeSpecs,
        excludeUnsupportedTargets,
        false,
        applyDefaultFlavorsMode);
  }

  private synchronized TargetGraphAndBuildTargets buildTargetGraphForTargetNodeSpecs(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      boolean excludeUnsupportedTargets,
      boolean excludeConfigurationTargets,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, IOException, InterruptedException {

    AtomicLong processedBytes = new AtomicLong();
    try (PerBuildState state =
        perBuildStateFactory.create(
            permState,
            executor,
            rootCell,
            targetPlatforms.get(),
            enableProfiling,
            processedBytes,
            SpeculativeParsing.ENABLED)) {

      ImmutableSet<BuildTarget> buildTargets =
          collectBuildTargetsFromTargetNodeSpecs(
              rootCell,
              state,
              targetNodeSpecs,
              excludeUnsupportedTargets,
              excludeConfigurationTargets,
              applyDefaultFlavorsMode);
      TargetGraph graph = buildTargetGraph(state, buildTargets, processedBytes);

      return TargetGraphAndBuildTargets.of(graph, buildTargets);
    }
  }

  @SuppressWarnings("unused")
  protected ImmutableSet<BuildTarget> collectBuildTargetsFromTargetNodeSpecs(
      Cell rootCell,
      PerBuildState state,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      boolean excludeUnsupportedTargets,
      boolean excludeConfigurationTargets,
      ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws IOException, InterruptedException {
    TargetNodeProviderForSpecResolver<TargetNode<?>> targetNodeProvider =
        createTargetNodeProviderForSpecResolver(state);

    return ImmutableSet.copyOf(
        Iterables.concat(
            targetSpecResolver.resolveTargetSpecs(
                eventBus,
                rootCell,
                watchman,
                targetNodeSpecs,
                (buildTarget, targetNode, targetType) ->
                    applyDefaultFlavors(
                        buildTarget, targetNode, targetType, applyDefaultFlavorsMode),
                targetNodeProvider,
                (spec, nodes) -> spec.filter(nodes))));
  }

  static TargetNodeProviderForSpecResolver<TargetNode<?>> createTargetNodeProviderForSpecResolver(
      PerBuildState state) {
    return new TargetNodeProviderForSpecResolver<TargetNode<?>>() {
      @Override
      public ListenableFuture<TargetNode<?>> getTargetNodeJob(BuildTarget target)
          throws BuildTargetException {
        return state.getTargetNodeJob(target);
      }

      @Override
      public ListenableFuture<ImmutableList<TargetNode<?>>> getAllTargetNodesJob(
          Cell cell, Path buildFile) throws BuildTargetException {
        return state.getAllTargetNodesJob(cell, buildFile);
      }
    };
  }

  @Override
  public String toString() {
    return permState.toString();
  }

  @Override
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> specs,
      SpeculativeParsing speculativeParsing,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, InterruptedException, IOException {

    try (PerBuildState state =
        perBuildStateFactory.create(
            permState,
            executor,
            rootCell,
            targetPlatforms.get(),
            enableProfiling,
            speculativeParsing)) {
      TargetNodeProviderForSpecResolver<TargetNode<?>> targetNodeProvider =
          createTargetNodeProviderForSpecResolver(state);
      return targetSpecResolver.resolveTargetSpecs(
          eventBus,
          rootCell,
          watchman,
          specs,
          (buildTarget, targetNode, targetType) ->
              applyDefaultFlavors(buildTarget, targetNode, targetType, applyDefaultFlavorsMode),
          targetNodeProvider,
          (spec, nodes) -> spec.filter(nodes));
    }
  }

  @Override
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> specs,
      SpeculativeParsing speculativeParsing,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode,
      boolean excludeUnsupportedTargets)
      throws BuildFileParseException, InterruptedException, IOException {
    return resolveTargetSpecs(
        rootCell, enableProfiling, executor, specs, speculativeParsing, applyDefaultFlavorsMode);
  }

  @VisibleForTesting
  static BuildTarget applyDefaultFlavors(
      BuildTarget target,
      Optional<TargetNode<?>> targetNode,
      TargetNodeSpec.TargetType targetType,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode) {
    if (target.isFlavored()
        || !targetNode.isPresent()
        || (targetType == TargetNodeSpec.TargetType.MULTIPLE_TARGETS
            && applyDefaultFlavorsMode == ParserConfig.ApplyDefaultFlavorsMode.SINGLE)
        || applyDefaultFlavorsMode == ParserConfig.ApplyDefaultFlavorsMode.DISABLED) {
      return target;
    }

    TargetNode<?> node = targetNode.get();

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
}
