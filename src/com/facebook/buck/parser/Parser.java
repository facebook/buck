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

import com.facebook.buck.counters.Counter;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.WatchEvents;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.HasDefaultFlavors;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreMaps;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

/**
 * High-level build file parsing machinery. Primarily responsible for producing a
 * {@link TargetGraph} based on a set of targets. Caches build rules to minimise the number of calls
 * to python and processes filesystem WatchEvents to invalidate the cache as files change.
 */
public class Parser {

  private static final Logger LOG = Logger.get(Parser.class);

  private final DaemonicParserState permState;
  private final ConstructorArgMarshaller marshaller;

  public Parser(
      ParserConfig parserConfig,
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller) {
    this.permState = new DaemonicParserState(
        typeCoercerFactory,
        parserConfig.getNumParsingThreads());
    this.marshaller = marshaller;
  }

  @VisibleForTesting
  ImmutableList<Map<String, Object>> getRawTargetNodes(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Path buildFile) throws InterruptedException, BuildFileParseException {
    Preconditions.checkState(buildFile.isAbsolute());
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    try (
        PerBuildState state =
            new PerBuildState(
                permState,
                marshaller,
                eventBus,
                executor,
                cell,
                enableProfiling,
                SpeculativeParsing.of(false),
                /* ignoreBuckAutodepsFiles */ false)) {
      return state.getAllRawNodes(cell, buildFile);
    }
  }

  public ImmutableSet<TargetNode<?>> getAllTargetNodes(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Path buildFile) throws BuildFileParseException {
    Preconditions.checkState(
        buildFile.isAbsolute(),
        "Build file should be referred to using an absolute path: %s",
        buildFile);
    Preconditions.checkState(
        buildFile.startsWith(cell.getRoot()),
        "Roots do not match %s -> %s",
        cell.getRoot(),
        buildFile);

    try (PerBuildState state = new PerBuildState(
        permState,
        marshaller,
        eventBus,
        executor,
        cell,
        enableProfiling,
        SpeculativeParsing.of(true),
        /* ignoreBuckAutodepsFiles */ false)) {
      return state.getAllTargetNodes(cell, buildFile);
    }
  }

  public TargetNode<?> getTargetNode(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      BuildTarget target)
      throws BuildFileParseException, BuildTargetException {
    try (
        PerBuildState state =
            new PerBuildState(
                permState,
                marshaller,
                eventBus,
                executor,
                cell,
                enableProfiling,
                SpeculativeParsing.of(false),
                /* ignoreBuckAutodepsFiles */ false)) {
      return state.getTargetNode(target);
    }
  }

  @Nullable
  public SortedMap<String, Object> getRawTargetNode(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      TargetNode<?> targetNode) throws InterruptedException, BuildFileParseException {

    try {
      Cell owningCell = cell.getCell(targetNode.getBuildTarget());
      ImmutableList<Map<String, Object>> allRawNodes = getRawTargetNodes(
          eventBus,
          owningCell,
          enableProfiling,
          executor,
          cell.getAbsolutePathToBuildFile(targetNode.getBuildTarget()));

      String shortName = targetNode.getBuildTarget().getShortName();
      for (Map<String, Object> rawNode : allRawNodes) {
        if (shortName.equals(rawNode.get("name"))) {
          SortedMap<String, Object> toReturn = new TreeMap<>();
          toReturn.putAll(rawNode);
          toReturn.put(
              "buck.direct_dependencies",
              FluentIterable.from(targetNode.getDeps())
                  .transform(Functions.toStringFunction())
                  .toList());
          return toReturn;
        }
      }
    } catch (Cell.MissingBuildFileException e) {
      throw new RuntimeException("Deeply unlikely to be true: the cell is missing: " + targetNode);
    }
    return null;
  }

  private RuntimeException propagateRuntimeCause(RuntimeException e)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    Throwables.propagateIfInstanceOf(e, HumanReadableException.class);
    Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
    Throwables.propagateIfInstanceOf(e.getCause(), InterruptedException.class);
    Throwables.propagateIfInstanceOf(e.getCause(), BuildFileParseException.class);
    Throwables.propagateIfInstanceOf(e.getCause(), BuildTargetException.class);
    return e;
  }

  public TargetGraph buildTargetGraph(
      final BuckEventBus eventBus,
      final Cell rootCell,
      final boolean enableProfiling,
      ListeningExecutorService executor,
      final Iterable<BuildTarget> toExplore)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    if (Iterables.isEmpty(toExplore)) {
      return TargetGraph.EMPTY;
    }

    try (final PerBuildState state =
             new PerBuildState(
                 permState,
                 marshaller,
                 eventBus,
                 executor,
                 rootCell,
                 enableProfiling,
                 SpeculativeParsing.of(true),
                 /* ignoreBuckAutodepsFiles */ false)) {
      return buildTargetGraph(
          state,
          eventBus,
          toExplore,
          /* ignoreBuckAutodepsFiles */ false);
    }
  }

  private TargetGraph buildTargetGraph(
      final PerBuildState state,
      final BuckEventBus eventBus,
      final Iterable<BuildTarget> toExplore,
      final boolean ignoreBuckAutodepsFiles)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {

    if (Iterables.isEmpty(toExplore)) {
      return TargetGraph.EMPTY;
    }

    final MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    final Map<BuildTarget, TargetNode<?>> index = new HashMap<>();

    ParseEvent.Started parseStart = ParseEvent.started(toExplore);
    eventBus.post(parseStart);

    GraphTraversable<BuildTarget> traversable = new GraphTraversable<BuildTarget>() {
      @Override
      public Iterator<BuildTarget> findChildren(BuildTarget target) {
        TargetNode<?> node;
        try (SimplePerfEvent.Scope scope = getTargetNodeEventScope(eventBus, target)) {
          try {
            node = state.getTargetNode(target);
          } catch (BuildFileParseException | BuildTargetException e) {
            throw new RuntimeException(e);
          }
        }

        if (ignoreBuckAutodepsFiles) {
          return Collections.emptyIterator();
        }

        Set<BuildTarget> deps = Sets.newHashSet();
        for (BuildTarget dep : node.getDeps()) {
          TargetNode<?> depTargetNode;
          try (SimplePerfEvent.Scope scope = getTargetNodeEventScope(eventBus, dep)) {
            try {
              depTargetNode = state.getTargetNode(dep);
            } catch (BuildFileParseException | BuildTargetException | HumanReadableException e) {
              throw new HumanReadableException(
                  e,
                  "Couldn't get dependency '%s' of target '%s':\n%s",
                  dep,
                  target,
                  e.getMessage());
            }
          }
          depTargetNode.checkVisibility(node);
          deps.add(dep);
        }
        return deps.iterator();
      }
    };

    AcyclicDepthFirstPostOrderTraversal<BuildTarget> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(traversable);

    TargetGraph targetGraph = null;
    try {
      for (BuildTarget target : traversal.traverse(toExplore)) {
        TargetNode<?> targetNode = state.getTargetNode(target);

        Preconditions.checkNotNull(targetNode, "No target node found for %s", target);
        graph.addNode(targetNode);
        MoreMaps.putCheckEquals(index, target, targetNode);
        if (target.isFlavored()) {
          BuildTarget unflavoredTarget = BuildTarget.of(target.getUnflavoredBuildTarget());
          MoreMaps.putCheckEquals(
              index,
              unflavoredTarget,
              state.getTargetNode(unflavoredTarget));
        }
        for (BuildTarget dep : targetNode.getDeps()) {
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
      eventBus.post(ParseEvent.finished(parseStart, Optional.fromNullable(targetGraph)));
    }
  }

  /**
   * @param eventBus used to log events while parsing.
   * @param targetNodeSpecs the specs representing the build targets to generate a target graph for.
   * @param ignoreBuckAutodepsFiles If true, do not load deps from {@code BUCK.autodeps} files.
   * @return the target graph containing the build targets and their related targets.
   */
  public synchronized TargetGraphAndBuildTargets
  buildTargetGraphForTargetNodeSpecs(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      boolean ignoreBuckAutodepsFiles)
    throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {
    return buildTargetGraphForTargetNodeSpecs(
        eventBus,
        rootCell,
        enableProfiling,
        executor,
        targetNodeSpecs,
        ignoreBuckAutodepsFiles,
        ParserConfig.ApplyDefaultFlavorsMode.DISABLED);
  }

  /**
   * @param eventBus used to log events while parsing.
   * @param targetNodeSpecs the specs representing the build targets to generate a target graph for.
   * @param ignoreBuckAutodepsFiles If true, do not load deps from {@code BUCK.autodeps} files.
   * @return the target graph containing the build targets and their related targets.
   */
  public synchronized TargetGraphAndBuildTargets
  buildTargetGraphForTargetNodeSpecs(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      boolean ignoreBuckAutodepsFiles,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, BuildTargetException, IOException, InterruptedException {

    try (PerBuildState state =
             new PerBuildState(
                 permState,
                 marshaller,
                 eventBus,
                 executor,
                 rootCell,
                 enableProfiling,
                 SpeculativeParsing.of(true),
                 ignoreBuckAutodepsFiles)) {

      ImmutableSet<BuildTarget> buildTargets = resolveTargetSpecs(
          state,
          eventBus,
          rootCell,
          targetNodeSpecs,
          applyDefaultFlavorsMode);
      TargetGraph graph = buildTargetGraph(state, eventBus, buildTargets, ignoreBuckAutodepsFiles);

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

  public ImmutableSet<BuildTarget> resolveTargetSpecs(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> specs,
      SpeculativeParsing speculativeParsing,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {

    try (PerBuildState state =
            new PerBuildState(
                permState,
                marshaller,
                eventBus,
                executor,
                rootCell,
                enableProfiling,
                speculativeParsing,
                /* ignoreBuckAutodepsFiles */ false)) {
      return resolveTargetSpecs(
          state,
          eventBus,
          rootCell,
          specs,
          applyDefaultFlavorsMode);
    }
  }

  private ImmutableSet<BuildTarget> resolveTargetSpecs(
      PerBuildState state,
      BuckEventBus eventBus,
      Cell rootCell,
      Iterable<? extends TargetNodeSpec> specs,
      final ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, BuildTargetException, InterruptedException, IOException {

    ParserConfig parserConfig = new ParserConfig(rootCell.getBuckConfig());

    ParserConfig.BuildFileSearchMethod buildFileSearchMethod;
    if (parserConfig.getBuildFileSearchMethod().isPresent()) {
      buildFileSearchMethod = parserConfig.getBuildFileSearchMethod().get();
    } else if (parserConfig.getAllowSymlinks() == ParserConfig.AllowSymlinks.FORBID) {
      // If unspecified, only use Watchman in repositories which enforce a "no symlinks" rule
      // (Watchman doesn't follow symlinks).
      buildFileSearchMethod = ParserConfig.BuildFileSearchMethod.WATCHMAN;
    } else {
      buildFileSearchMethod = ParserConfig.BuildFileSearchMethod.FILESYSTEM_CRAWL;
    }

    ImmutableList.Builder<ListenableFuture<ImmutableSet<BuildTarget>>>
        targetFutures = ImmutableList.builder();
    for (final TargetNodeSpec spec : specs) {
      Cell cell = rootCell.getCell(spec.getBuildFileSpec().getCellPath());
      ImmutableSet<Path> buildFiles;
      try (SimplePerfEvent.Scope perfEventScope = SimplePerfEvent.scope(
          eventBus,
          PerfEventId.of("FindBuildFiles"),
          "targetNodeSpec",
          spec)) {
        // Iterate over the build files the given target node spec returns.
        buildFiles = spec.getBuildFileSpec().findBuildFiles(
            cell,
            buildFileSearchMethod);
      }

      for (Path buildFile : buildFiles) {
        // Format a proper error message for non-existent build files.
        if (!cell.getFilesystem().isFile(buildFile)) {
          throw new MissingBuildFileException(
              spec,
              cell.getFilesystem().getRootPath().relativize(buildFile));
        }
        // Build up a list of all target nodes from the build file.
        targetFutures.add(
            Futures.transform(
                state.getAllTargetNodesJob(cell, buildFile),
                new Function<ImmutableSet<TargetNode<?>>,
                             ImmutableSet<BuildTarget>>() {
                  @Override
                  public ImmutableSet<BuildTarget> apply(
                      ImmutableSet<TargetNode<?>> nodes) {
                    // Call back into the target node spec to filter the relevant build targets.
                    return applySpecFilter(spec, nodes, applyDefaultFlavorsMode);
                  }
                }));
      }
    }

    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    try {
      for (ImmutableSet<BuildTarget> partialTargets :
               Futures.allAsList(targetFutures.build()).get()) {
        targets.addAll(partialTargets);
      }
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), BuildFileParseException.class);
      Throwables.propagateIfInstanceOf(e.getCause(), BuildTargetException.class);
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw ParsePipeline.propagateRuntimeException(e);
    }
    return targets.build();
  }

  private static ImmutableSet<BuildTarget> applySpecFilter(
      TargetNodeSpec spec,
      ImmutableSet<TargetNode<?>> targetNodes,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    ImmutableMap<BuildTarget, Optional<TargetNode<?>>> partialTargets =
        spec.filter(targetNodes);
    for (Map.Entry<BuildTarget, Optional<TargetNode<?>>> partialTarget :
             partialTargets.entrySet()) {
      BuildTarget target = applyDefaultFlavors(
            partialTarget.getKey(),
            partialTarget.getValue(),
            spec.getTargetType(),
            applyDefaultFlavorsMode);
      targets.add(target);
    }
    return targets.build();
  }

  private static BuildTarget applyDefaultFlavors(
      BuildTarget target,
      Optional<TargetNode<?>> targetNode,
      TargetNodeSpec.TargetType targetType,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode) {
    if (target.isFlavored() ||
        !targetNode.isPresent() ||
        targetType == TargetNodeSpec.TargetType.MULTIPLE_TARGETS ||
        applyDefaultFlavorsMode == ParserConfig.ApplyDefaultFlavorsMode.DISABLED) {
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
          ((ImplicitFlavorsInferringDescription)
           node.getDescription()).addImplicitFlavors(defaultFlavors);
      LOG.debug(
          "Got default flavors %s from description of %s",
          defaultFlavors,
          target);
    }

    return target.withFlavors(defaultFlavors);
  }

  static SimplePerfEvent.Scope getTargetNodeEventScope(
      BuckEventBus eventBus,
      BuildTarget buildTarget) {
    return SimplePerfEvent.scope(
        eventBus,
        PerfEventId.of("GetTargetNode"),
        "target", buildTarget);
  }

  @Subscribe
  public void onFileSystemChange(WatchEvent<?> event) throws InterruptedException {
    LOG.debug(
        "Parser watched event %s %s",
        event.kind(),
        WatchEvents.createContextString(event));

    permState.invalidateBasedOn(event);
  }

  public void recordParseStartTime(BuckEventBus eventBus) {
    LOG.debug(eventBus.toString());
    // Does nothing
  }

  public Optional<BuckEvent> getParseStartTime() {
    return Optional.absent();
  }

  public ImmutableList<Counter> getCounters() {
    return permState.getCounters();
  }

  @VisibleForTesting
  DaemonicParserState getPermState() {
    return permState;
  }
}
