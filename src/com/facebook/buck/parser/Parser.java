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
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.WatchmanOverflowEvent;
import com.facebook.buck.io.WatchmanPathEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasDefaultFlavors;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.parser.thrift.RemoteDaemonicParserState;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.KnownBuildRuleTypesProvider;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.MoreThrowables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * High-level build file parsing machinery. Primarily responsible for producing a {@link
 * TargetGraph} based on a set of targets. Caches build rules to minimise the number of calls to
 * python and processes filesystem WatchEvents to invalidate the cache as files change.
 */
public class Parser {

  private static final Logger LOG = Logger.get(Parser.class);

  private final DaemonicParserState permState;
  private final ConstructorArgMarshaller marshaller;
  private final TypeCoercerFactory typeCoercerFactory;
  private final KnownBuildRuleTypesProvider knownBuildRuleTypesProvider;
  private final ParserPythonInterpreterProvider parserPythonInterpreterProvider;

  public Parser(
      BroadcastEventListener broadcastEventListener,
      ParserConfig parserConfig,
      TypeCoercerFactory typeCoercerFactory,
      ConstructorArgMarshaller marshaller,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      ExecutableFinder executableFinder) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.permState =
        new DaemonicParserState(
            broadcastEventListener,
            typeCoercerFactory,
            parserConfig.getNumParsingThreads(),
            parserConfig.shouldIgnoreEnvironmentVariablesChanges());
    this.marshaller = marshaller;
    this.knownBuildRuleTypesProvider = knownBuildRuleTypesProvider;
    this.parserPythonInterpreterProvider =
        new ParserPythonInterpreterProvider(parserConfig, executableFinder);
  }

  public DaemonicParserState getPermState() {
    return permState;
  }

  @VisibleForTesting
  static ImmutableSet<Map<String, Object>> getRawTargetNodes(
      PerBuildState state, Cell cell, Path buildFile) throws BuildFileParseException {
    Preconditions.checkState(buildFile.isAbsolute());
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));
    return state.getAllRawNodes(cell, buildFile);
  }

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
        new PerBuildState(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            cell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            PerBuildState.SpeculativeParsing.ENABLED)) {
      return state.getAllTargetNodes(cell, buildFile);
    }
  }

  public TargetNode<?, ?> getTargetNode(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      BuildTarget target)
      throws BuildFileParseException {
    try (PerBuildState state =
        new PerBuildState(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            cell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            PerBuildState.SpeculativeParsing.DISABLED)) {
      return state.getTargetNode(target);
    }
  }

  @Nullable
  public SortedMap<String, Object> getRawTargetNode(
      PerBuildState state, Cell cell, TargetNode<?, ?> targetNode) throws BuildFileParseException {
    try {

      Cell owningCell = cell.getCell(targetNode.getBuildTarget());
      ImmutableSet<Map<String, Object>> allRawNodes =
          getRawTargetNodes(
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
   * @deprecated Prefer {@link #getRawTargetNode(PerBuildState, Cell, TargetNode)} and reusing a
   *     PerBuildState instance, especially when calling in a loop.
   */
  @Nullable
  @Deprecated
  public SortedMap<String, Object> getRawTargetNode(
      BuckEventBus eventBus,
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      TargetNode<?, ?> targetNode)
      throws BuildFileParseException {

    try (PerBuildState state =
        new PerBuildState(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            cell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            PerBuildState.SpeculativeParsing.DISABLED)) {
      return getRawTargetNode(state, cell, targetNode);
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
        new PerBuildState(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            rootCell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            PerBuildState.SpeculativeParsing.ENABLED)) {
      return buildTargetGraph(state, eventBus, toExplore);
    }
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  protected TargetGraph buildTargetGraph(
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
          BuildTarget unflavoredTarget = BuildTarget.of(target.getUnflavoredBuildTarget());
          MoreMaps.putCheckEquals(index, unflavoredTarget, state.getTargetNode(unflavoredTarget));
        }
        for (BuildTarget dep : targetNode.getParseDeps()) {
          graph.addEdge(targetNode, state.getTargetNode(dep));
        }
      }

      targetGraph = new TargetGraph(graph, ImmutableMap.copyOf(index));
      state.ensureConcreteFilesExist(eventBus);
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
  public synchronized TargetGraphAndBuildTargets buildTargetGraphForTargetNodeSpecs(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, IOException, InterruptedException {

    try (PerBuildState state =
        new PerBuildState(
            typeCoercerFactory,
            permState,
            marshaller,
            eventBus,
            parserPythonInterpreterProvider,
            executor,
            rootCell,
            knownBuildRuleTypesProvider,
            enableProfiling,
            PerBuildState.SpeculativeParsing.ENABLED)) {

      ImmutableSet<BuildTarget> buildTargets =
          ImmutableSet.copyOf(
              Iterables.concat(
                  resolveTargetSpecs(
                      state, eventBus, rootCell, targetNodeSpecs, applyDefaultFlavorsMode)));
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

  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      BuckEventBus eventBus,
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> specs,
      PerBuildState.SpeculativeParsing speculativeParsing,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, InterruptedException, IOException {

    try (PerBuildState state =
        new PerBuildState(
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
      return resolveTargetSpecs(state, eventBus, rootCell, specs, applyDefaultFlavorsMode);
    }
  }

  private ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      PerBuildState state,
      BuckEventBus eventBus,
      Cell rootCell,
      Iterable<? extends TargetNodeSpec> specs,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, InterruptedException, IOException {

    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);

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

    // Convert the input spec iterable into a list so we have a fixed ordering, which we'll rely on
    // when returning results.
    ImmutableList<TargetNodeSpec> orderedSpecs = ImmutableList.copyOf(specs);

    // Resolve all the build files from all the target specs.  We store these into a multi-map which
    // maps the path to the build file to the index of it's spec file in the ordered spec list.
    Multimap<Path, Integer> perBuildFileSpecs = LinkedHashMultimap.create();
    for (int index = 0; index < orderedSpecs.size(); index++) {
      TargetNodeSpec spec = orderedSpecs.get(index);
      Cell cell = rootCell.getCell(spec.getBuildFileSpec().getCellPath());
      ImmutableSet<Path> buildFiles;
      try (SimplePerfEvent.Scope perfEventScope =
          SimplePerfEvent.scope(
              eventBus, PerfEventId.of("FindBuildFiles"), "targetNodeSpec", spec)) {
        // Iterate over the build files the given target node spec returns.
        buildFiles = spec.getBuildFileSpec().findBuildFiles(cell, buildFileSearchMethod);
      }
      for (Path buildFile : buildFiles) {
        perBuildFileSpecs.put(buildFile, index);
      }
    }

    // Kick off parse futures for each build file.
    ArrayList<ListenableFuture<Map.Entry<Integer, ImmutableSet<BuildTarget>>>> targetFutures =
        new ArrayList<>();
    for (Path buildFile : perBuildFileSpecs.keySet()) {
      Collection<Integer> buildFileSpecs = perBuildFileSpecs.get(buildFile);
      TargetNodeSpec firstSpec = orderedSpecs.get(Iterables.get(buildFileSpecs, 0));
      Cell cell = rootCell.getCell(firstSpec.getBuildFileSpec().getCellPath());

      // Format a proper error message for non-existent build files.
      if (!cell.getFilesystem().isFile(buildFile)) {
        throw new MissingBuildFileException(
            firstSpec.toString(), cell.getFilesystem().getRootPath().relativize(buildFile));
      }

      for (int index : buildFileSpecs) {
        TargetNodeSpec spec = orderedSpecs.get(index);
        if (spec instanceof BuildTargetSpec) {
          BuildTargetSpec buildTargetSpec = (BuildTargetSpec) spec;
          targetFutures.add(
              Futures.transform(
                  state.getTargetNodeJob(buildTargetSpec.getBuildTarget()),
                  node -> {
                    ImmutableSet<BuildTarget> buildTargets =
                        applySpecFilter(spec, ImmutableSet.of(node), applyDefaultFlavorsMode);
                    Preconditions.checkState(
                        buildTargets.size() == 1,
                        "BuildTargetSpec %s filter discarded target %s, but was not supposed to.",
                        spec,
                        node.getBuildTarget());
                    return new AbstractMap.SimpleEntry<>(index, buildTargets);
                  },
                  MoreExecutors.directExecutor()));
        } else {
          // Build up a list of all target nodes from the build file.
          targetFutures.add(
              Futures.transform(
                  state.getAllTargetNodesJob(cell, buildFile),
                  nodes ->
                      new AbstractMap.SimpleEntry<>(
                          index, applySpecFilter(spec, nodes, applyDefaultFlavorsMode)),
                  MoreExecutors.directExecutor()));
        }
      }
    }

    // Now walk through and resolve all the futures, and place their results in a multimap that
    // is indexed by the integer representing the input target spec order.
    LinkedHashMultimap<Integer, BuildTarget> targetsMap = LinkedHashMultimap.create();
    try {
      for (ListenableFuture<Map.Entry<Integer, ImmutableSet<BuildTarget>>> targetFuture :
          targetFutures) {
        Map.Entry<Integer, ImmutableSet<BuildTarget>> result = targetFuture.get();
        targetsMap.putAll(result.getKey(), result.getValue());
      }
    } catch (ExecutionException e) {
      MoreThrowables.throwIfAnyCauseInstanceOf(e, BuildFileParseException.class);
      MoreThrowables.throwIfAnyCauseInstanceOf(e, BuildTargetException.class);
      MoreThrowables.throwIfAnyCauseInstanceOf(e, HumanReadableException.class);
      MoreThrowables.throwIfAnyCauseInstanceOf(e, InterruptedException.class);
      Throwables.throwIfUnchecked(e.getCause());
      throw new RuntimeException(e);
    }

    // Finally, pull out the final build target results in input target spec order, and place them
    // into a list of sets that exactly matches the ihput order.
    ImmutableList.Builder<ImmutableSet<BuildTarget>> targets = ImmutableList.builder();
    for (int index = 0; index < orderedSpecs.size(); index++) {
      targets.add(ImmutableSet.copyOf(targetsMap.get(index)));
    }
    return targets.build();
  }

  private static ImmutableSet<BuildTarget> applySpecFilter(
      TargetNodeSpec spec,
      ImmutableSet<TargetNode<?, ?>> targetNodes,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    ImmutableMap<BuildTarget, Optional<TargetNode<?, ?>>> partialTargets = spec.filter(targetNodes);
    for (Map.Entry<BuildTarget, Optional<TargetNode<?, ?>>> partialTarget :
        partialTargets.entrySet()) {
      BuildTarget target =
          applyDefaultFlavors(
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

  public RemoteDaemonicParserState storeParserState(Cell rootCell) throws IOException {
    return getPermState().serializeDaemonicParserState(rootCell);
  }

  public void restoreParserState(RemoteDaemonicParserState state, Cell rootCell) {
    getPermState().restoreState(state, rootCell);
  }

  @Subscribe
  public void onFileSystemChange(WatchmanOverflowEvent event) {
    LOG.verbose("Parser watched event OVERFLOW %s", event.getReason());
    permState.invalidateBasedOn(event);
  }

  @Subscribe
  public void onFileSystemChange(WatchmanPathEvent event) {
    LOG.verbose("Parser watched event %s %s", event.getKind(), event.getPath());

    permState.invalidateBasedOn(event);
  }

  public void invalidateBasedOnPath(Path fullPath, boolean isCreatedOrDeleted) {
    permState.invalidateBasedOnPath(fullPath, isCreatedOrDeleted);
  }

  public void recordParseStartTime(BuckEventBus eventBus) {
    LOG.debug(eventBus.toString());
    // Does nothing
  }

  public Optional<BuckEvent> getParseStartTime() {
    return Optional.empty();
  }

  public ImmutableList<Counter> getCounters() {
    return permState.getCounters();
  }
}
