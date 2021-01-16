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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableExceptions;
import com.facebook.buck.core.files.DirectoryListCache;
import com.facebook.buck.core.files.DirectoryListComputation;
import com.facebook.buck.core.files.FileTreeCache;
import com.facebook.buck.core.files.FileTreeComputation;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.impl.DefaultGraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.parser.BuildPackagePaths;
import com.facebook.buck.core.parser.BuildTargetPatternToBuildPackagePathComputation;
import com.facebook.buck.core.parser.BuildTargetPatternToBuildPackagePathKey;
import com.facebook.buck.core.parser.WatchmanBuildPackageComputation;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.parser.spec.BuildFileSpec;
import com.facebook.buck.parser.spec.BuildTargetSpec;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.util.MoreThrowables;
import com.facebook.buck.util.function.TriFunction;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/** Responsible for discovering all the build targets that match a set of {@link TargetNodeSpec}. */
public class TargetSpecResolver implements AutoCloseable {

  private final BuckEventBus eventBus;

  // transformation engine to be used when full file tree of given path is required
  // because different cells might have different filesystems, we have to store each engine
  // instance per each cell object
  // A better design would be to make TargetSpecResolver cell-centric, i.e. have resolver work
  // in a scope of a Cell only
  private final LoadingCache<CanonicalCellName, GraphTransformationEngine>
      graphEngineForRecursiveSpecPerCell;

  public static final Duration DEFAULT_WATCHMAN_TIMEOUT = Duration.ofSeconds(30);

  @FunctionalInterface
  private interface EngineFactory {
    GraphTransformationEngine makeEngine(
        Path cellPath,
        String buildFileName,
        ProjectFilesystemView fileSystemView,
        Duration timeout);
  }

  private TargetSpecResolver(
      BuckEventBus eventBus,
      LoadingCache<CanonicalCellName, GraphTransformationEngine>
          graphEngineForRecursiveSpecPerCell) {
    this.eventBus = eventBus;
    this.graphEngineForRecursiveSpecPerCell = graphEngineForRecursiveSpecPerCell;
  }

  private TargetSpecResolver(
      BuckEventBus eventBus, CellProvider cellProvider, EngineFactory engineFactory) {
    this(
        eventBus,
        CacheBuilder.newBuilder()
            .build(
                CacheLoader.from(
                    name -> {
                      Cell cell = cellProvider.getCellByCanonicalCellName(name);
                      ParserConfig config = cell.getBuckConfigView(ParserConfig.class);
                      String buildFileName = config.getBuildFileName();
                      ProjectFilesystemView fileSystemView = cell.getFilesystemViewForSourceFiles();
                      Duration timeout =
                          config
                              .getWatchmanQueryTimeoutMs()
                              .map(Duration::ofMillis)
                              .orElse(DEFAULT_WATCHMAN_TIMEOUT);
                      return engineFactory.makeEngine(
                          cell.getRoot().getPath(), buildFileName, fileSystemView, timeout);
                    })));
  }

  /**
   * Create {@link TargetSpecResolver instance} using {@link
   * BuildTargetPatternToBuildPackagePathComputation}
   *
   * @param eventBus Event bus to send performance events to
   * @param executor The executor for the {@link GraphTransformationEngine}
   * @param cellProvider Provider to get a cell by path; this is a workaround for the state that
   *     cell itself is not really hashable so we use cell path instead as a key for appropriate
   *     caches
   * @param dirListCachePerRoot Global cache that stores a mapping of cell root path to a cache of
   *     all directory structures under that cell
   * @param fileTreeCachePerRoot Global cache that stores a mapping of cell root path to a cache of
   *     all file tree structures under that cell
   */
  public static TargetSpecResolver createWithFileSystemCrawler(
      BuckEventBus eventBus,
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      CellProvider cellProvider,
      LoadingCache<Path, DirectoryListCache> dirListCachePerRoot,
      LoadingCache<Path, FileTreeCache> fileTreeCachePerRoot) {
    // For each cell we create a separate graph engine. The purpose of graph engine is to
    // recursively build a file tree with all files in appropriate cell for appropriate path.
    // This file tree will later be used to resolve target pattern to a list of build files
    // where those targets are defined.
    // For example, for target pattern like //project/folder/... it will return all files and
    // folders
    // under [cellroot]/project/folder recursively as FileTree object. We then traverse FileTree
    // object looking for a build file name in all subfolders recursively.
    // Graph Engines automatically ensures right amount of parallelism and does caching of the data.
    return new TargetSpecResolver(
        eventBus,
        cellProvider,
        (Path cellPath,
            String buildFileName,
            ProjectFilesystemView fileSystemView,
            Duration watchmanTimeout) -> {
          DirectoryListCache dirListCache = dirListCachePerRoot.getUnchecked(cellPath);
          Verify.verifyNotNull(
              dirListCache,
              "Injected directory list cache map does not have cell %s",
              fileSystemView.getRootPath());

          FileTreeCache fileTreeCache = fileTreeCachePerRoot.getUnchecked(cellPath);
          Verify.verifyNotNull(
              fileTreeCache,
              "Injected file tree cache map does not have cell %s",
              fileSystemView.getRootPath());

          return new DefaultGraphTransformationEngine(
              ImmutableList.of(
                  new GraphComputationStage<>(
                      BuildTargetPatternToBuildPackagePathComputation.of(
                          buildFileName, fileSystemView)),
                  new GraphComputationStage<>(
                      DirectoryListComputation.of(fileSystemView), dirListCache),
                  new GraphComputationStage<>(FileTreeComputation.of(), fileTreeCache)),
              16,
              executor);
        });
  }

  /**
   * Create {@link TargetSpecResolver instance} using {@link WatchmanBuildPackageComputation}
   *
   * @param eventBus Event bus to send performance events to
   * @param executor The executor for the {@link GraphTransformationEngine}
   * @param cellProvider Provider to get a cell by path; this is a workaround for the state that
   *     cell itself is not really hashable so we use cell path instead as a key for appropriate
   *     caches
   */
  public static TargetSpecResolver createWithWatchmanCrawler(
      BuckEventBus eventBus,
      Watchman watchman,
      DepsAwareExecutor<? super ComputeResult, ?> executor,
      CellProvider cellProvider) {
    return new TargetSpecResolver(
        eventBus,
        cellProvider,
        (Path cellPath,
            String buildFileName,
            ProjectFilesystemView fileSystemView,
            Duration timeout) ->
            new DefaultGraphTransformationEngine(
                ImmutableList.of(
                    new GraphComputationStage<>(
                        new WatchmanBuildPackageComputation(
                            buildFileName, fileSystemView, watchman, timeout))),
                1,
                executor));
  }

  /**
   * @return a list of sets of build targets where each set contains all build targets that match a
   *     corresponding {@link TargetNodeSpec}.
   */
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      Cells cells,
      Iterable<? extends TargetNodeSpec> specs,
      Optional<TargetConfiguration> targetConfiguration,
      FlavorEnhancer flavorEnhancer,
      PerBuildState perBuildState,
      TargetNodeFilterForSpecResolver<BuildTarget, TargetNodeMaybeIncompatible> targetNodeFilter)
      throws BuildFileParseException, InterruptedException {
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(eventBus.isolated(), "target_spec_resolver.resolve_target_specs")) {
      return resolveSpecsGeneric(
          cells,
          specs,
          (cell, path, spec) ->
              handleTargetNodeSpec(
                  flavorEnhancer,
                  perBuildState,
                  targetNodeFilter,
                  cell,
                  targetConfiguration,
                  path,
                  spec));
    }
  }

  /**
   * @return a list of sets of unflavored build targets where each set contains all build targets
   *     that match a corresponding {@link TargetNodeSpec}
   */
  public ImmutableList<ImmutableSet<UnflavoredBuildTarget>> resolveTargetSpecsUnconfigured(
      Cells cells,
      Iterable<? extends TargetNodeSpec> specs,
      PerBuildState perBuildState,
      TargetNodeFilterForSpecResolver<UnflavoredBuildTarget, UnconfiguredTargetNode>
          targetNodeFilter)
      throws BuildFileParseException, InterruptedException {
    return resolveSpecsGeneric(
        cells,
        specs,
        (cell, path, spec) ->
            handleTargetNodeSpecUnconfigured(perBuildState, targetNodeFilter, cell, path, spec));
  }

  private <T> ImmutableList<ImmutableSet<T>> resolveSpecsGeneric(
      Cells cells,
      Iterable<? extends TargetNodeSpec> specs,
      TriFunction<Cell, AbsPath, TargetNodeSpec, ListenableFuture<ImmutableSet<T>>>
          handleSpecFunction)
      throws BuildFileParseException, InterruptedException {

    // Convert the input spec iterable into a list so we have a fixed ordering, which we'll rely on
    // when returning results.
    ImmutableList<TargetNodeSpec> orderedSpecs = ImmutableList.copyOf(specs);

    Multimap<AbsPath, Integer> perBuildFileSpecs =
        groupSpecsByBuildFile(cells.getRootCell(), orderedSpecs);

    // Kick off parse futures for each build file.
    ArrayList<ListenableFuture<Map.Entry<Integer, ImmutableSet<T>>>> targetFutures =
        new ArrayList<>();
    for (AbsPath buildFile : perBuildFileSpecs.keySet()) {
      Collection<Integer> buildFileSpecs = perBuildFileSpecs.get(buildFile);
      TargetNodeSpec firstSpec = orderedSpecs.get(Iterables.get(buildFileSpecs, 0));
      Cell cell =
          cells
              .getCellProvider()
              .getCellByCanonicalCellName(
                  firstSpec.getBuildFileSpec().getCellRelativeBaseName().getCellName());

      // Format a proper error message for non-existent build files.
      if (!cell.getFilesystem().isFile(buildFile)) {
        throw new MissingBuildFileException(
            DependencyStack.root(),
            firstSpec.toString(),
            cell.getFilesystem().relativize(buildFile).getPath());
      }

      for (int index : buildFileSpecs) {
        TargetNodeSpec spec = orderedSpecs.get(index);
        targetFutures.add(
            Futures.transform(
                handleSpecFunction.apply(cell, buildFile, spec),
                targets -> new AbstractMap.SimpleEntry<>(index, targets),
                MoreExecutors.directExecutor()));
      }
    }

    return collectTargets(orderedSpecs.size(), targetFutures);
  }

  // Resolve all the build files from all the target specs.  We store these into a multi-map which
  // maps the path to the build file to the index of it's spec file in the ordered spec list.
  private Multimap<AbsPath, Integer> groupSpecsByBuildFile(
      Cell rootCell, ImmutableList<TargetNodeSpec> orderedSpecs) {

    Multimap<AbsPath, Integer> perBuildFileSpecs = LinkedHashMultimap.create();
    for (int index = 0; index < orderedSpecs.size(); index++) {
      TargetNodeSpec spec = orderedSpecs.get(index);
      CanonicalCellName cellName = spec.getBuildFileSpec().getCellRelativeBaseName().getCellName();
      Cell cell = rootCell.getCellProvider().getCellByCanonicalCellName(cellName);
      try (SimplePerfEvent.Scope perfEventScope =
          SimplePerfEvent.scope(
              eventBus.isolated(),
              SimplePerfEvent.PerfEventTitle.of("FindBuildFiles"),
              "targetNodeSpec",
              spec)) {

        BuildFileSpec buildFileSpec = spec.getBuildFileSpec();
        ProjectFilesystemView projectFilesystemView = cell.getFilesystemViewForSourceFiles();
        if (!buildFileSpec.isRecursive()) {
          // If spec is not recursive, i.e. //path/to:something, then we only need to look for
          // build file under base path
          AbsPath buildFile =
              AbsPath.of(
                  projectFilesystemView.resolve(
                      buildFileSpec
                          .getCellRelativeBaseName()
                          .getPath()
                          .resolve(cell.getBuckConfigView(ParserConfig.class).getBuildFileName())));
          perBuildFileSpecs.put(buildFile, index);
        } else {
          // For recursive spec, i.e. //path/to/... we use cached file tree
          BuildTargetPattern pattern = spec.getBuildTargetPattern(cell);
          BuildPackagePaths paths =
              graphEngineForRecursiveSpecPerCell
                  .getUnchecked(cell.getCanonicalName())
                  .computeUnchecked(BuildTargetPatternToBuildPackagePathKey.of(pattern));

          String buildFileName = cell.getBuckConfigView(ParserConfig.class).getBuildFileName();
          for (Path path : paths.getPackageRoots()) {
            perBuildFileSpecs.put(
                AbsPath.of(projectFilesystemView.resolve(path).resolve(buildFileName)), index);
          }
        }
      }
    }
    return perBuildFileSpecs;
  }

  private ListenableFuture<ImmutableSet<BuildTarget>> handleTargetNodeSpec(
      FlavorEnhancer flavorEnhancer,
      PerBuildState perBuildState,
      TargetNodeFilterForSpecResolver<BuildTarget, TargetNodeMaybeIncompatible> targetNodeFilter,
      Cell cell,
      Optional<TargetConfiguration> targetConfiguration,
      AbsPath buildFile,
      TargetNodeSpec spec) {
    if (spec instanceof BuildTargetSpec) {
      BuildTargetSpec buildTargetSpec = (BuildTargetSpec) spec;
      return Futures.transform(
          perBuildState.getRequestedTargetNodeJob(
              buildTargetSpec.getUnconfiguredBuildTarget(), targetConfiguration),
          node -> {
            return applySpecFilterAndFlavorEnhancer(
                spec, ImmutableList.of(node), flavorEnhancer, targetNodeFilter);
          },
          MoreExecutors.directExecutor());
    } else {
      // Build up a list of all target nodes from the build file.
      return Futures.transform(
          perBuildState.getRequestedTargetNodesJob(cell, buildFile, targetConfiguration),
          nodes -> applySpecFilterAndFlavorEnhancer(spec, nodes, flavorEnhancer, targetNodeFilter),
          MoreExecutors.directExecutor());
    }
  }

  private ListenableFuture<ImmutableSet<UnflavoredBuildTarget>> handleTargetNodeSpecUnconfigured(
      PerBuildState perBuildState,
      TargetNodeFilterForSpecResolver<UnflavoredBuildTarget, UnconfiguredTargetNode>
          targetNodeFilter,
      Cell cell,
      AbsPath buildFile,
      TargetNodeSpec spec) {
    if (spec instanceof BuildTargetSpec) {
      BuildTargetSpec buildTargetSpec = (BuildTargetSpec) spec;
      return Futures.transform(
          perBuildState.getUnconfiguredTargetNodeJob(
              buildTargetSpec.getUnconfiguredBuildTarget(), DependencyStack.root()),
          node -> {
            ImmutableSet<UnflavoredBuildTarget> buildTargets =
                applySpecFilter(spec, ImmutableList.of(node), targetNodeFilter).keySet();
            Preconditions.checkState(
                buildTargets.size() == 1,
                "BuildTargetSpec %s filter discarded target %s, but was not supposed to.",
                spec,
                node.getBuildTarget());
            return buildTargets;
          },
          MoreExecutors.directExecutor());
    } else {
      // Build up a list of all target nodes from the build file.
      return Futures.transform(
          perBuildState.getAllUnconfiguredTargetNodesJobs(cell, buildFile),
          nodes -> applySpecFilter(spec, nodes, targetNodeFilter).keySet(),
          MoreExecutors.directExecutor());
    }
  }

  private <T> ImmutableList<ImmutableSet<T>> collectTargets(
      int specsCount, List<ListenableFuture<Entry<Integer, ImmutableSet<T>>>> targetFutures)
      throws InterruptedException {
    // Walk through and resolve all the futures, and place their results in a multimap that
    // is indexed by the integer representing the input target spec order.
    LinkedHashMultimap<Integer, T> targetsMap = LinkedHashMultimap.create();
    try {
      for (ListenableFuture<Map.Entry<Integer, ImmutableSet<T>>> targetFuture : targetFutures) {
        Map.Entry<Integer, ImmutableSet<T>> result = targetFuture.get();
        targetsMap.putAll(result.getKey(), result.getValue());
      }
    } catch (ExecutionException e) {
      MoreThrowables.throwIfAnyCauseInstanceOf(e, InterruptedException.class);
      HumanReadableExceptions.throwIfHumanReadableUnchecked(e.getCause());
      throw new RuntimeException(e);
    }
    // Finally, pull out the final build target results in input target spec order, and place them
    // into a list of sets that exactly matches the ihput order.
    ImmutableList.Builder<ImmutableSet<T>> targets = ImmutableList.builder();
    for (int index = 0; index < specsCount; index++) {
      targets.add(ImmutableSet.copyOf(targetsMap.get(index)));
    }
    return targets.build();
  }

  /** Applies a SpecFilter<T, N> */
  private <T, N> ImmutableMap<T, N> applySpecFilter(
      TargetNodeSpec spec,
      ImmutableList<N> targetNodes,
      TargetNodeFilterForSpecResolver<T, N> targetNodeFilter) {
    return targetNodeFilter.filter(spec, targetNodes);
  }

  private ImmutableSet<BuildTarget> applySpecFilterAndFlavorEnhancer(
      TargetNodeSpec spec,
      ImmutableList<TargetNodeMaybeIncompatible> targetNodes,
      FlavorEnhancer flavorEnhancer,
      TargetNodeFilterForSpecResolver<BuildTarget, TargetNodeMaybeIncompatible> targetNodeFilter) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    ImmutableMap<BuildTarget, TargetNodeMaybeIncompatible> partialTargets =
        applySpecFilter(spec, targetNodes, targetNodeFilter);
    for (Map.Entry<BuildTarget, TargetNodeMaybeIncompatible> partialTarget :
        partialTargets.entrySet()) {
      BuildTarget target =
          flavorEnhancer.enhanceFlavors(
              partialTarget.getKey(), partialTarget.getValue(), spec.getTargetType());
      targets.add(target);
    }
    return targets.build();
  }

  @Override
  public void close() {
    graphEngineForRecursiveSpecPerCell.asMap().values().forEach(engine -> engine.close());
  }

  /** Allows to change flavors of some targets while performing the resolution. */
  public interface FlavorEnhancer {
    BuildTarget enhanceFlavors(
        BuildTarget target,
        TargetNodeMaybeIncompatible targetNode,
        TargetNodeSpec.TargetType targetType);
  }

  /**
   * Performs filtering of target nodes using a given {@link TargetNodeSpec}. `T` refers to the
   * Target type (eg BuildTarget or UnflavoredBuildTarget) and `N` refers to the Node type (eg
   * `TargetNodeMaybeIncompatible or UnconfiguredTargetNode)
   */
  public interface TargetNodeFilterForSpecResolver<T, N> {
    ImmutableMap<T, N> filter(TargetNodeSpec spec, Iterable<N> nodes);
  }
}
