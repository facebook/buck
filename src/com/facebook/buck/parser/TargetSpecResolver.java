/*
 * Copyright 2018-present Facebook, Inc.
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
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.files.DirectoryListCache;
import com.facebook.buck.core.files.DirectoryListComputation;
import com.facebook.buck.core.files.FileTree;
import com.facebook.buck.core.files.FileTreeCache;
import com.facebook.buck.core.files.FileTreeComputation;
import com.facebook.buck.core.files.FileTreeFileNameIterator;
import com.facebook.buck.core.files.ImmutableFileTreeKey;
import com.facebook.buck.core.graph.transformation.DefaultGraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.GraphComputationStage;
import com.facebook.buck.core.graph.transformation.GraphTransformationEngine;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasBuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.util.MoreThrowables;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

/** Responsible for discovering all the build targets that match a set of {@link TargetNodeSpec}. */
public class TargetSpecResolver implements AutoCloseable {

  private final BuckEventBus eventBus;

  // transformation engine to be used when full file tree of given path is required
  // because different cells might have different filesystems, we have to store each engine
  // instance per each cell object
  // A better design would be to make TargetSpecResolver cell-centric, i.e. have resolver work
  // in a scope of a Cell only
  private final LoadingCache<Path, GraphTransformationEngine> graphEngineForRecursiveSpecPerRoot;

  /**
   * Create {@link TargetSpecResolver instance}
   *
   * @param eventBus Event bus to send performance events to
   * @param numThreads Number of threads to be used by Graph Engine executor
   * @param cellProvider Provider to get a cell by path; this is a workaround for the state that
   *     cell itself is not really hashable so we use cell path instead as a key for appropriate
   *     caches
   * @param dirListCachePerRoot Global cache that stores a mapping of cell root path to a cache of
   *     all directory structures under that cell
   * @param fileTreeCachePerRoot Global cache that stores a mapping of cell root path to a cache of
   *     all file tree structures under that cell
   */
  public TargetSpecResolver(
      BuckEventBus eventBus,
      int numThreads,
      CellProvider cellProvider,
      LoadingCache<Path, DirectoryListCache> dirListCachePerRoot,
      LoadingCache<Path, FileTreeCache> fileTreeCachePerRoot) {
    this.eventBus = eventBus;

    // TODO(buck_team): pass executor from upstream
    DepsAwareExecutor<ComputeResult, ?> executor = DefaultDepsAwareExecutor.of(numThreads);

    // For each cell we create a separate graph engine. The purpose of graph engine is to
    // recursively build a file tree with all files in appropriate cell for appropriate path.
    // This file tree will later be used to resolve target pattern to a list of build files
    // where those targets are defined.
    // For example, for target pattern like //project/folder/... it will return all files and
    // folders
    // under [cellroot]/project/folder recursively as FileTree object. We then traverse FileTree
    // object looking for a build file name in all subfolders recursively.
    // Graph Engines automatically ensures right amount of parallelism and does caching of the data.
    graphEngineForRecursiveSpecPerRoot =
        CacheBuilder.newBuilder()
            .build(
                CacheLoader.from(
                    path -> {
                      ProjectFilesystemView fileSystemView =
                          cellProvider.getCellByPath(path).getFilesystemViewForSourceFiles();

                      DirectoryListCache dirListCache = dirListCachePerRoot.getUnchecked(path);
                      Verify.verifyNotNull(
                          dirListCache,
                          "Injected directory list cache map does not have cell %s",
                          fileSystemView.getRootPath());

                      FileTreeCache fileTreeCache = fileTreeCachePerRoot.getUnchecked(path);
                      Verify.verifyNotNull(
                          fileTreeCache,
                          "Injected file tree cache map does not have cell %s",
                          fileSystemView.getRootPath());

                      return new DefaultGraphTransformationEngine(
                          ImmutableList.of(
                              new GraphComputationStage<>(
                                  DirectoryListComputation.of(fileSystemView), dirListCache),
                              new GraphComputationStage<>(FileTreeComputation.of(), fileTreeCache)),
                          16,
                          executor);
                    }));
  }

  /**
   * @return a list of sets of build targets where each set contains all build targets that match a
   *     corresponding {@link TargetNodeSpec}.
   */
  public <T extends HasBuildTarget> ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      Cell rootCell,
      Iterable<? extends TargetNodeSpec> specs,
      TargetConfiguration targetConfiguration,
      FlavorEnhancer<T> flavorEnhancer,
      TargetNodeProviderForSpecResolver<T> targetNodeProvider,
      TargetNodeFilterForSpecResolver<T> targetNodeFilter)
      throws BuildFileParseException, InterruptedException {

    // Convert the input spec iterable into a list so we have a fixed ordering, which we'll rely on
    // when returning results.
    ImmutableList<TargetNodeSpec> orderedSpecs = ImmutableList.copyOf(specs);

    Multimap<Path, Integer> perBuildFileSpecs = groupSpecsByBuildFile(rootCell, orderedSpecs);

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
        handleTargetNodeSpec(
            flavorEnhancer,
            targetNodeProvider,
            targetNodeFilter,
            targetFutures,
            cell,
            buildFile,
            targetConfiguration,
            index,
            spec);
      }
    }

    return collectTargets(orderedSpecs.size(), targetFutures);
  }

  // Resolve all the build files from all the target specs.  We store these into a multi-map which
  // maps the path to the build file to the index of it's spec file in the ordered spec list.
  private Multimap<Path, Integer> groupSpecsByBuildFile(
      Cell rootCell, ImmutableList<TargetNodeSpec> orderedSpecs) {

    Multimap<Path, Integer> perBuildFileSpecs = LinkedHashMultimap.create();
    for (int index = 0; index < orderedSpecs.size(); index++) {
      TargetNodeSpec spec = orderedSpecs.get(index);
      Path cellPath = spec.getBuildFileSpec().getCellPath();
      Cell cell = rootCell.getCell(cellPath);
      try (SimplePerfEvent.Scope perfEventScope =
          SimplePerfEvent.scope(
              eventBus, PerfEventId.of("FindBuildFiles"), "targetNodeSpec", spec)) {

        BuildFileSpec buildFileSpec = spec.getBuildFileSpec();
        ProjectFilesystemView projectFilesystemView = cell.getFilesystemViewForSourceFiles();
        if (!buildFileSpec.isRecursive()) {
          // If spec is not recursive, i.e. //path/to:something, then we only need to look for
          // build file under base path
          Path buildFile =
              projectFilesystemView.resolve(
                  buildFileSpec
                      .getBasePath()
                      .resolve(cell.getBuckConfigView(ParserConfig.class).getBuildFileName()));
          perBuildFileSpecs.put(buildFile, index);
        } else {
          // For recursive spec, i.e. //path/to/... we use cached file tree
          Path basePath = spec.getBuildFileSpec().getBasePath();

          // sometimes spec comes with absolute path as base path, sometimes it is relative to
          // cell path
          // TODO(sergeyb): find out why
          if (basePath.isAbsolute()) {
            basePath = cellPath.relativize(basePath);
          }
          FileTree fileTree =
              graphEngineForRecursiveSpecPerRoot
                  .getUnchecked(cellPath)
                  .computeUnchecked(ImmutableFileTreeKey.of(basePath));

          for (Path path :
              FileTreeFileNameIterator.ofIterable(
                  fileTree, cell.getBuckConfigView(ParserConfig.class).getBuildFileName())) {
            perBuildFileSpecs.put(projectFilesystemView.resolve(path), index);
          }
        }
      }
    }
    return perBuildFileSpecs;
  }

  private <T extends HasBuildTarget> void handleTargetNodeSpec(
      FlavorEnhancer<T> flavorEnhancer,
      TargetNodeProviderForSpecResolver<T> targetNodeProvider,
      TargetNodeFilterForSpecResolver<T> targetNodeFilter,
      List<ListenableFuture<Map.Entry<Integer, ImmutableSet<BuildTarget>>>> targetFutures,
      Cell cell,
      Path buildFile,
      TargetConfiguration targetConfiguration,
      int index,
      TargetNodeSpec spec) {
    if (spec instanceof BuildTargetSpec) {
      BuildTargetSpec buildTargetSpec = (BuildTargetSpec) spec;
      targetFutures.add(
          Futures.transform(
              targetNodeProvider.getTargetNodeJob(
                  buildTargetSpec.getUnconfiguredBuildTargetView().configure(targetConfiguration)),
              node -> {
                ImmutableSet<BuildTarget> buildTargets =
                    applySpecFilter(spec, ImmutableList.of(node), flavorEnhancer, targetNodeFilter);
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
              targetNodeProvider.getAllTargetNodesJob(cell, buildFile, targetConfiguration),
              nodes ->
                  new AbstractMap.SimpleEntry<>(
                      index, applySpecFilter(spec, nodes, flavorEnhancer, targetNodeFilter)),
              MoreExecutors.directExecutor()));
    }
  }

  private ImmutableList<ImmutableSet<BuildTarget>> collectTargets(
      int specsCount,
      List<ListenableFuture<Entry<Integer, ImmutableSet<BuildTarget>>>> targetFutures)
      throws InterruptedException {
    // Walk through and resolve all the futures, and place their results in a multimap that
    // is indexed by the integer representing the input target spec order.
    LinkedHashMultimap<Integer, BuildTarget> targetsMap = LinkedHashMultimap.create();
    try {
      for (ListenableFuture<Map.Entry<Integer, ImmutableSet<BuildTarget>>> targetFuture :
          targetFutures) {
        Map.Entry<Integer, ImmutableSet<BuildTarget>> result = targetFuture.get();
        targetsMap.putAll(result.getKey(), result.getValue());
      }
    } catch (ExecutionException e) {
      MoreThrowables.throwIfAnyCauseInstanceOf(e, InterruptedException.class);
      Throwables.throwIfUnchecked(e.getCause());
      throw new RuntimeException(e);
    }
    // Finally, pull out the final build target results in input target spec order, and place them
    // into a list of sets that exactly matches the ihput order.
    ImmutableList.Builder<ImmutableSet<BuildTarget>> targets = ImmutableList.builder();
    for (int index = 0; index < specsCount; index++) {
      targets.add(ImmutableSet.copyOf(targetsMap.get(index)));
    }
    return targets.build();
  }

  private <T extends HasBuildTarget> ImmutableSet<BuildTarget> applySpecFilter(
      TargetNodeSpec spec,
      ImmutableList<T> targetNodes,
      FlavorEnhancer<T> flavorEnhancer,
      TargetNodeFilterForSpecResolver<T> targetNodeFilter) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    ImmutableMap<BuildTarget, T> partialTargets = targetNodeFilter.filter(spec, targetNodes);
    for (Map.Entry<BuildTarget, T> partialTarget : partialTargets.entrySet()) {
      BuildTarget target =
          flavorEnhancer.enhanceFlavors(
              partialTarget.getKey(), partialTarget.getValue(), spec.getTargetType());
      targets.add(target);
    }
    return targets.build();
  }

  @Override
  public void close() {
    graphEngineForRecursiveSpecPerRoot.asMap().values().forEach(engine -> engine.close());
  }

  /** Allows to change flavors of some targets while performing the resolution. */
  public interface FlavorEnhancer<T extends HasBuildTarget> {
    BuildTarget enhanceFlavors(
        BuildTarget target, T targetNode, TargetNodeSpec.TargetType targetType);
  }

  /** Provides target nodes of a given type. */
  public interface TargetNodeProviderForSpecResolver<T extends HasBuildTarget> {
    ListenableFuture<T> getTargetNodeJob(BuildTarget target) throws BuildTargetException;

    ListenableFuture<ImmutableList<T>> getAllTargetNodesJob(
        Cell cell, Path buildFile, TargetConfiguration targetConfiguration)
        throws BuildTargetException;
  }

  /** Performs filtering of target nodes using a given {@link TargetNodeSpec}. */
  public interface TargetNodeFilterForSpecResolver<T extends HasBuildTarget> {
    ImmutableMap<BuildTarget, T> filter(TargetNodeSpec spec, Iterable<T> nodes);
  }
}
