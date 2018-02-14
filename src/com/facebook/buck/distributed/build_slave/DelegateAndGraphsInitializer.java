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

import com.facebook.buck.config.ActionGraphParallelizationMode;
import com.facebook.buck.distributed.DistBuildCachingEngineDelegate;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildTargetGraphCodec;
import com.facebook.buck.distributed.build_slave.BuildSlaveTimingStatsTracker.SlaveEvents;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.CachingBuildEngineDelegate;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.LocalCachingBuildEngineDelegate;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;

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

  public ListenableFuture<ActionGraphAndResolver> getActionGraphAndResolver() {
    return Futures.transform(
        delegateAndGraphs, x -> x.getActionGraphAndResolver(), MoreExecutors.directExecutor());
  }

  private DelegateAndGraphs createDelegateAndGraphs() throws IOException, InterruptedException {
    StackedFileHashCaches stackedCaches = createStackedFileHashesAndPreload();
    TargetGraph targetGraph = createTargetGraph();
    ActionGraphAndResolver actionGraphAndResolver = createActionGraphAndResolver(targetGraph);
    CachingBuildEngineDelegate engineDelegate =
        createBuildEngineDelegate(stackedCaches, actionGraphAndResolver);
    return DelegateAndGraphs.builder()
        .setTargetGraph(targetGraph)
        .setActionGraphAndResolver(actionGraphAndResolver)
        .setCachingBuildEngineDelegate(engineDelegate)
        .build();
  }

  private TargetGraph createTargetGraph() throws IOException, InterruptedException {
    args.getTimingStatsTracker().startTimer(SlaveEvents.TARGET_GRAPH_DESERIALIZATION_TIME);
    try {
      TargetGraph targetGraph = null;
      DistBuildTargetGraphCodec codec = createGraphCodec();
      ImmutableMap<Integer, Cell> cells = args.getState().getCells();
      TargetGraphAndBuildTargets targetGraphAndBuildTargets =
          Preconditions.checkNotNull(
              codec.createTargetGraph(
                  args.getState().getRemoteState().getTargetGraph(),
                  key -> Preconditions.checkNotNull(cells.get(key)),
                  args.getKnownBuildRuleTypesProvider()));

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
  private ActionGraphAndResolver createActionGraphAndResolver(TargetGraph targetGraph)
      throws IOException, InterruptedException {
    args.getTimingStatsTracker().startTimer(SlaveEvents.ACTION_GRAPH_CREATION_TIME);
    try {
      ActionGraphAndResolver actionGraphAndResolver =
          args.getActionGraphCache()
              .getActionGraph(
                  args.getBuckEventBus(),
                  /* checkActionGraphs */ false,
                  /* skipActionGraphCache */ false,
                  Preconditions.checkNotNull(targetGraph),
                  args.getRuleKeyConfiguration(),
                  ActionGraphParallelizationMode.DISABLED,
                  Optional.empty(),
                  args.getShouldInstrumentActionGraph(),
                  CloseableMemoizedSupplier.of(
                      () -> {
                        throw new IllegalStateException(
                            "should not use parallel executor for action graph construction in distributed slave build");
                      },
                      ignored -> {}));
      return actionGraphAndResolver;
    } finally {
      args.getTimingStatsTracker().stopTimer(SlaveEvents.ACTION_GRAPH_CREATION_TIME);
    }
  }

  /** Creates the delegate for the distributed build. */
  private CachingBuildEngineDelegate createBuildEngineDelegate(
      StackedFileHashCaches caches, ActionGraphAndResolver actionGraphAndResolver)
      throws IOException, InterruptedException {
    CachingBuildEngineDelegate cachingBuildEngineDelegate = null;
    DistBuildConfig remoteConfig = new DistBuildConfig(args.getState().getRemoteRootCellConfig());
    if (remoteConfig.materializeSourceFilesOnDemand()) {
      SourcePathRuleFinder ruleFinder =
          new SourcePathRuleFinder(
              Preconditions.checkNotNull(actionGraphAndResolver).getResolver());
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
    ParserTargetNodeFactory<TargetNode<?, ?>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory),
            args.getRuleKeyConfiguration());

    return new DistBuildTargetGraphCodec(
        parserTargetNodeFactory,
        input -> {
          try {
            return args.getParser()
                .getRawTargetNode(
                    args.getBuckEventBus(),
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

  private StackedFileHashCaches createStackedFileHashesAndPreload()
      throws InterruptedException, IOException {
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
                          Preconditions.checkNotNull(args.getExecutors().get(ExecutorPool.CPU)));
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
