/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.core.build.engine.delegate.CachingBuildEngineDelegate;
import com.facebook.buck.core.build.engine.delegate.LocalCachingBuildEngineDelegate;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.DistBuildCachingEngineDelegate;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildTargetGraphCodec;
import com.facebook.buck.distributed.build_slave.BuildSlaveTimingStatsTracker.SlaveEvents;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.versions.VersionException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/** Initializes the build engine delegate, the target graph and the action graph. */
public class DelegateAndGraphsInitializer {

  private static final Logger LOG = Logger.get(DelegateAndGraphsInitializer.class);

  private final DelegateAndGraphsInitializerArgs args;
  private final ListenableFuture<DelegateAndGraphs> delegateAndGraphs;

  public DelegateAndGraphsInitializer(DelegateAndGraphsInitializerArgs args) {
    this.args = args;
    this.delegateAndGraphs =
        args.getExecutorService()
            .submit(
                () -> {
                  try {
                    return createDelegateAndGraphs();
                  } catch (InterruptedException | IOException e) {
                    LOG.error(
                        e, "Critical failure while creating the build engine delegate and graphs.");
                    throw new RuntimeException(e);
                  }
                });
  }

  public ListenableFuture<DelegateAndGraphs> getDelegateAndGraphs() {
    return delegateAndGraphs;
  }

  public ListenableFuture<ActionGraphAndBuilder> getActionGraphAndBuilder() {
    return Futures.transform(
        delegateAndGraphs, x -> x.getActionGraphAndBuilder(), MoreExecutors.directExecutor());
  }

  private DelegateAndGraphs createDelegateAndGraphs() throws IOException, InterruptedException {
    LOG.info("Starting to preload source files.");
    StackedFileHashCaches stackedCaches = createStackedFileHashesAndPreload();
    LOG.info("Finished pre-loading source files.");
    LOG.info("Starting to create the target graph.");
    TargetGraph targetGraph = createTargetGraph();
    LOG.info("Finished creating the target graph.");
    LOG.info("Starting to create the action graph.");
    ActionGraphAndBuilder actionGraphAndBuilder = createActionGraphAndResolver(targetGraph);
    LOG.info("Finished creating the action graph.");
    CachingBuildEngineDelegate engineDelegate =
        createBuildEngineDelegate(stackedCaches, actionGraphAndBuilder);
    return DelegateAndGraphs.builder()
        .setTargetGraph(targetGraph)
        .setActionGraphAndBuilder(actionGraphAndBuilder)
        .setCachingBuildEngineDelegate(engineDelegate)
        .build();
  }

  private TargetGraph createTargetGraph() throws InterruptedException {
    args.getTimingStatsTracker().startTimer(SlaveEvents.TARGET_GRAPH_DESERIALIZATION_TIME);
    try {
      TargetGraph targetGraph = null;
      DistBuildTargetGraphCodec codec = createGraphCodec();
      ImmutableMap<Integer, Cell> cells = args.getState().getCells();
      TargetGraphAndBuildTargets targetGraphAndBuildTargets =
          Objects.requireNonNull(
              codec.createTargetGraph(
                  args.getState().getRemoteState().getTargetGraph(),
                  key -> Objects.requireNonNull(cells.get(key))));

      try {
        if (args.getState().getRemoteRootCellConfig().getBuildVersions()) {
          targetGraph =
              args.getVersionedTargetGraphCache()
                  .toVersionedTargetGraph(
                      args.getBuckEventBus(),
                      args.getState().getRemoteRootCellConfig(),
                      new DefaultTypeCoercerFactory(
                          PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY),
                      targetGraphAndBuildTargets)
                  .getTargetGraph();
        } else {
          targetGraph = targetGraphAndBuildTargets.getTargetGraph();
        }
      } catch (VersionException e) {
        throw new RuntimeException(e);
      }

      return targetGraph;
    } finally {
      args.getTimingStatsTracker().stopTimer(SlaveEvents.TARGET_GRAPH_DESERIALIZATION_TIME);
    }
  }

  // TODO(ruibm): This thing is time consuming and should execute in the background.
  private ActionGraphAndBuilder createActionGraphAndResolver(TargetGraph targetGraph) {
    args.getTimingStatsTracker().startTimer(SlaveEvents.ACTION_GRAPH_CREATION_TIME);
    try {
      return args.getActionGraphProvider().getActionGraph(Objects.requireNonNull(targetGraph));
    } finally {
      args.getTimingStatsTracker().stopTimer(SlaveEvents.ACTION_GRAPH_CREATION_TIME);
    }
  }

  /** Creates the delegate for the distributed build. */
  private CachingBuildEngineDelegate createBuildEngineDelegate(
      StackedFileHashCaches caches, ActionGraphAndBuilder actionGraphAndBuilder) {
    CachingBuildEngineDelegate cachingBuildEngineDelegate = null;
    DistBuildConfig remoteConfig = new DistBuildConfig(args.getState().getRemoteRootCellConfig());
    if (remoteConfig.materializeSourceFilesOnDemand()) {
      SourcePathRuleFinder ruleFinder =
          new SourcePathRuleFinder(
              Objects.requireNonNull(actionGraphAndBuilder).getActionGraphBuilder());
      cachingBuildEngineDelegate =
          new DistBuildCachingEngineDelegate(
              DefaultSourcePathResolver.from(ruleFinder),
              ruleFinder,
              caches.remoteStateCache,
              caches.materializingCache,
              this.args.getRuleKeyConfiguration(),
              this.args.getDistBuildConfig().getFileMaterializationTimeoutSecs());
    } else {
      cachingBuildEngineDelegate = new LocalCachingBuildEngineDelegate(caches.remoteStateCache);
    }

    return cachingBuildEngineDelegate;
  }

  private StackedFileHashCache createStackOfDefaultFileHashCache() throws InterruptedException {
    ImmutableList.Builder<ProjectFileHashCache> allCachesBuilder = ImmutableList.builder();
    Cell rootCell = args.getState().getRootCell();

    // 1. Add all cells (including the root cell).
    for (Path cellPath : rootCell.getKnownRoots()) {
      Cell cell = rootCell.getCell(cellPath);
      allCachesBuilder.add(
          DefaultFileHashCache.createDefaultFileHashCache(
              cell.getFilesystem(), rootCell.getBuckConfig().getFileHashCacheMode()));
      allCachesBuilder.add(
          DefaultFileHashCache.createBuckOutFileHashCache(
              cell.getFilesystem(), rootCell.getBuckConfig().getFileHashCacheMode()));
    }

    // 2. Add the Operating System roots.
    allCachesBuilder.addAll(
        DefaultFileHashCache.createOsRootDirectoriesCaches(
            args.getProjectFilesystemFactory(), rootCell.getBuckConfig().getFileHashCacheMode()));

    return new StackedFileHashCache(allCachesBuilder.build());
  }

  private DistBuildTargetGraphCodec createGraphCodec() {
    // Note: This is a hack. Do not confuse this hack with the other hack where we 'pre-load' all
    // files so that file existence checks in TG -> AG transformation pass (which is a bigger bug).
    // We need this hack in addition to the other one, because some source file dependencies get
    // shaved off in the versioned target graph, and so they don't get recorded in the distributed
    // state, and hence they're not pre-loaded. So even when we pre-load the files, we need this
    // hack so that the coercer does not check for existence of these unrecorded files.
    TypeCoercerFactory typeCoercerFactory =
        new DefaultTypeCoercerFactory(PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY);
    ParserTargetNodeFactory<Map<String, Object>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            args.getKnownRuleTypesProvider(),
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory),
            args.getRuleKeyConfiguration());

    return new DistBuildTargetGraphCodec(
        Objects.requireNonNull(args.getExecutorService()),
        parserTargetNodeFactory,
        input -> {
          try {
            return args.getParser()
                .getTargetNodeRawAttributes(
                    args.getState().getRootCell().getCell(input.getBuildTarget()),
                    /* enableProfiling */ false,
                    args.getExecutorService(),
                    input);
          } catch (BuildFileParseException e) {
            throw new RuntimeException(e);
          }
        },
        new HashSet<>(args.getState().getRemoteState().getTopLevelTargets()));
  }

  private static class StackedFileHashCaches {

    public final StackedFileHashCache remoteStateCache;
    public final StackedFileHashCache materializingCache;

    private StackedFileHashCaches(
        StackedFileHashCache remoteStateCache, StackedFileHashCache materializingCache) {
      this.remoteStateCache = remoteStateCache;
      this.materializingCache = materializingCache;
    }
  }

  private StackedFileHashCaches createStackedFileHashesAndPreload() throws InterruptedException {
    args.getTimingStatsTracker().startTimer(SlaveEvents.SOURCE_FILE_PRELOAD_TIME);
    try {
      StackedFileHashCache stackedFileHashCache = createStackOfDefaultFileHashCache();
      // Used for rule key computations.
      StackedFileHashCache remoteStackedFileHashCache =
          stackedFileHashCache.newDecoratedFileHashCache(
              cache -> args.getState().createRemoteFileHashCache(cache));

      // Used for the real build.
      StackedFileHashCache materializingStackedFileHashCache =
          stackedFileHashCache.newDecoratedFileHashCache(
              cache -> {
                try {
                  return args.getState()
                      .createMaterializerAndPreload(
                          cache,
                          args.getProvider(),
                          Objects.requireNonNull(args.getExecutors().get(ExecutorPool.CPU)));
                } catch (IOException exception) {
                  throw new RuntimeException(
                      String.format(
                          "Failed to create the Materializer for file system [%s]",
                          cache.getFilesystem()),
                      exception);
                }
              });

      return new StackedFileHashCaches(
          remoteStackedFileHashCache, materializingStackedFileHashCache);
    } finally {
      args.getTimingStatsTracker().stopTimer(SlaveEvents.SOURCE_FILE_PRELOAD_TIME);
    }
  }
}
