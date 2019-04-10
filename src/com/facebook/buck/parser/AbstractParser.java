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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.core.util.graph.GraphTraversable;
import com.facebook.buck.core.util.graph.MutableDirectedGraph;
import com.facebook.buck.event.BuckEventBus;
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
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
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
abstract class AbstractParser implements Parser {

  protected final PerBuildStateFactory perBuildStateFactory;
  protected final DaemonicParserState permState;
  protected final BuckEventBus eventBus;
  protected final Supplier<ImmutableList<String>> targetPlatforms;

  AbstractParser(
      DaemonicParserState daemonicParserState,
      PerBuildStateFactory perBuildStateFactory,
      BuckEventBus eventBus,
      Supplier<ImmutableList<String>> targetPlatforms) {
    this.perBuildStateFactory = perBuildStateFactory;
    this.permState = daemonicParserState;
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
      PerBuildState perBuildState,
      Cell cell,
      Path buildFile,
      TargetConfiguration targetConfiguration)
      throws BuildFileParseException {
    return perBuildState.getAllTargetNodes(cell, buildFile, targetConfiguration);
  }

  @Override
  public TargetNode<?> getTargetNode(ParsingContext parsingContext, BuildTarget target)
      throws BuildFileParseException {
    try (PerBuildState state =
        perBuildStateFactory.create(parsingContext, permState, targetPlatforms.get())) {
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
            state,
            owningCell,
            cell.getBuckConfigView(ParserConfig.class)
                .getAbsolutePathToBuildFile(
                    cell, targetNode.getBuildTarget().getUnconfiguredBuildTargetView()));
    return getTargetFromManifest(targetNode, buildFileManifest);
  }

  @Override
  public ListenableFuture<SortedMap<String, Object>> getTargetNodeRawAttributesJob(
      PerBuildState state, Cell cell, TargetNode<?> targetNode) throws BuildFileParseException {
    Cell owningCell = cell.getCell(targetNode.getBuildTarget());
    ListenableFuture<BuildFileManifest> buildFileManifestFuture =
        state.getBuildFileManifestJob(
            owningCell,
            cell.getBuckConfigView(ParserConfig.class)
                .getAbsolutePathToBuildFile(
                    cell, targetNode.getBuildTarget().getUnconfiguredBuildTargetView()));
    return Futures.transform(
        buildFileManifestFuture,
        buildFileManifest -> getTargetFromManifest(targetNode, buildFileManifest),
        MoreExecutors.directExecutor());
  }

  @Nullable
  private static SortedMap<String, Object> getTargetFromManifest(
      TargetNode<?> targetNode, BuildFileManifest buildFileManifest) {
    String shortName = targetNode.getBuildTarget().getShortName();

    if (!buildFileManifest.getTargets().containsKey(shortName)) {
      return null;
    }

    SortedMap<String, Object> attributes =
        new TreeMap<>(buildFileManifest.getTargets().get(shortName));
    attributes.put(
        InternalTargetAttributeNames.DIRECT_DEPENDENCIES,
        targetNode.getParseDeps().stream()
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
      ParsingContext parsingContext, TargetNode<?> targetNode) throws BuildFileParseException {

    try (PerBuildState state =
        perBuildStateFactory.create(parsingContext, permState, targetPlatforms.get())) {
      return getTargetNodeRawAttributes(state, parsingContext.getCell(), targetNode);
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
      ParsingContext parsingContext, Iterable<BuildTarget> toExplore)
      throws IOException, InterruptedException, BuildFileParseException {
    if (Iterables.isEmpty(toExplore)) {
      return TargetGraph.EMPTY;
    }

    AtomicLong processedBytes = new AtomicLong();
    try (PerBuildState state =
        perBuildStateFactory.create(
            parsingContext, permState, targetPlatforms.get(), processedBytes)) {
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
        assertTargetIsCompatible(state, targetNode);

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

  @Override
  public synchronized TargetGraphAndBuildTargets buildTargetGraphWithoutConfigurationTargets(
      ParsingContext parsingContext,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      TargetConfiguration targetConfiguration)
      throws BuildFileParseException, IOException, InterruptedException {
    return buildTargetGraphForTargetNodeSpecs(
        parsingContext, targetNodeSpecs, targetConfiguration, true);
  }

  @Override
  public synchronized TargetGraphAndBuildTargets buildTargetGraphWithConfigurationTargets(
      ParsingContext parsingContext,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      TargetConfiguration targetConfiguration)
      throws BuildFileParseException, IOException, InterruptedException {
    return buildTargetGraphForTargetNodeSpecs(
        parsingContext, targetNodeSpecs, targetConfiguration, false);
  }

  private synchronized TargetGraphAndBuildTargets buildTargetGraphForTargetNodeSpecs(
      ParsingContext parsingContext,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      TargetConfiguration targetConfiguration,
      boolean excludeConfigurationTargets)
      throws BuildFileParseException, IOException, InterruptedException {

    AtomicLong processedBytes = new AtomicLong();
    try (PerBuildState state =
        perBuildStateFactory.create(
            parsingContext, permState, targetPlatforms.get(), processedBytes)) {

      ImmutableSet<BuildTarget> buildTargets =
          collectBuildTargetsFromTargetNodeSpecs(
              parsingContext,
              state,
              targetNodeSpecs,
              targetConfiguration,
              excludeConfigurationTargets);
      TargetGraph graph = buildTargetGraph(state, buildTargets, processedBytes);

      return TargetGraphAndBuildTargets.of(graph, buildTargets);
    }
  }

  protected abstract ImmutableSet<BuildTarget> collectBuildTargetsFromTargetNodeSpecs(
      ParsingContext parsingContext,
      PerBuildState state,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      TargetConfiguration targetConfiguration,
      boolean excludeConfigurationTargets)
      throws InterruptedException;

  /**
   * Verifies that the provided target node is compatible with the target platform.
   *
   * @throws com.facebook.buck.core.exceptions.HumanReadableException if the target not is not
   *     compatible with the target platform.
   */
  @SuppressWarnings("unused")
  protected void assertTargetIsCompatible(PerBuildState state, TargetNode<?> targetNode) {}

  @Override
  public String toString() {
    return permState.toString();
  }
}
