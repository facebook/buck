/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.MetadataChecker;
import com.facebook.buck.command.Build;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.CachingBuildEngineBuckConfig;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.RuleKeyDiagnosticsMode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyCache;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.facebook.buck.versions.VersionException;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

public class DistBuildSlaveExecutor {
  private static final Logger LOG = Logger.get(DistBuildSlaveExecutor.class);

  private final DistBuildExecutorArgs args;

  @Nullable private TargetGraph targetGraph;

  @Nullable private ActionGraphAndResolver actionGraphAndResolver;

  @Nullable private DistBuildCachingEngineDelegate cachingBuildEngineDelegate;

  public DistBuildSlaveExecutor(DistBuildExecutorArgs args) {
    this.args = args;
  }

  public int buildAndReturnExitCode() throws IOException, InterruptedException {
    createBuildEngineDelegate();
    LocalBuilder localBuilder = new LocalBuilderImpl();

    DistBuildModeRunner runner = null;
    switch (args.getDistBuildMode()) {
      case REMOTE_BUILD:
        runner =
            new RemoteBuildModeRunner(
                localBuilder, args.getState().getRemoteState().getTopLevelTargets());
        break;

      case COORDINATOR:
        runner = newCoordinatorMode();
        break;

      case MINION:
        runner = newMinionMode(localBuilder);
        break;

      case COORDINATOR_AND_MINION:
        runner =
            new CoordinatorAndMinionModeRunner(newCoordinatorMode(), newMinionMode(localBuilder));
        break;

      default:
        LOG.error("Unknown distributed build mode [%s].", args.getDistBuildMode().toString());
        return -1;
    }

    return runner.runAndReturnExitCode();
  }

  private MinionModeRunner newMinionMode(LocalBuilder localBuilder) {
    return new MinionModeRunner(
        args.getCoordinatorAddress(),
        args.getCoordinatorPort(),
        localBuilder,
        args.getStampedeId());
  }

  private CoordinatorModeRunner newCoordinatorMode() {
    BuildTargetsQueue queue =
        BuildTargetsQueue.newQueue(
            Preconditions.checkNotNull(actionGraphAndResolver).getResolver(),
            fullyQualifiedNameToBuildTarget(args.getState().getRemoteState().getTopLevelTargets()));
    return new CoordinatorModeRunner(args.getCoordinatorPort(), queue, args.getStampedeId());
  }

  private TargetGraph createTargetGraph() throws IOException, InterruptedException {
    if (targetGraph != null) {
      return targetGraph;
    }

    DistBuildTargetGraphCodec codec = createGraphCodec();
    TargetGraphAndBuildTargets targetGraphAndBuildTargets =
        Preconditions.checkNotNull(
            codec.createTargetGraph(
                args.getState().getRemoteState().getTargetGraph(),
                Functions.forMap(args.getState().getCells())));

    try {
      if (args.getRemoteRootCellConfig().getBuildVersions()) {
        targetGraph =
            args.getVersionedTargetGraphCache()
                .toVersionedTargetGraph(
                    args.getBuckEventBus(),
                    args.getRemoteRootCellConfig(),
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
  }

  private ActionGraphAndResolver createActionGraphAndResolver()
      throws IOException, InterruptedException {
    if (actionGraphAndResolver != null) {
      return actionGraphAndResolver;
    }
    createTargetGraph();

    actionGraphAndResolver =
        Preconditions.checkNotNull(
            args.getActionGraphCache()
                .getActionGraph(
                    args.getBuckEventBus(),
                    /* checkActionGraphs */ false,
                    /* skipActionGraphCache */ false,
                    Preconditions.checkNotNull(targetGraph),
                    args.getCacheKeySeed()));
    return actionGraphAndResolver;
  }

  private DistBuildCachingEngineDelegate createBuildEngineDelegate()
      throws IOException, InterruptedException {
    if (cachingBuildEngineDelegate != null) {
      return cachingBuildEngineDelegate;
    }

    StackedFileHashCaches caches = createStackedFileHashesAndPreload();
    createActionGraphAndResolver();
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(Preconditions.checkNotNull(actionGraphAndResolver).getResolver());
    cachingBuildEngineDelegate =
        new DistBuildCachingEngineDelegate(
            new SourcePathResolver(ruleFinder),
            ruleFinder,
            caches.remoteStateCache,
            caches.materializingCache);
    return cachingBuildEngineDelegate;
  }

  private StackedFileHashCache createStackOfDefaultFileHashCache()
      throws InterruptedException, IOException {
    ImmutableList.Builder<ProjectFileHashCache> allCachesBuilder = ImmutableList.builder();
    Cell rootCell = args.getState().getRootCell();

    // 1. Add all cells (including the root cell).
    for (Path cellPath : rootCell.getKnownRoots()) {
      Cell cell = rootCell.getCell(cellPath);
      allCachesBuilder.add(DefaultFileHashCache.createDefaultFileHashCache(cell.getFilesystem()));
    }

    // 2. Add the Operating System roots.
    allCachesBuilder.addAll(DefaultFileHashCache.createOsRootDirectoriesCaches());

    return new StackedFileHashCache(allCachesBuilder.build());
  }

  private Supplier<AndroidPlatformTarget> getExplodingAndroidSupplier() {
    return AndroidPlatformTarget.EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER;
  }

  private List<BuildTarget> fullyQualifiedNameToBuildTarget(Iterable<String> buildTargets) {
    List<BuildTarget> targets = new ArrayList<>();
    CellPathResolver distBuildCellPathResolver =
        args.getState().getRootCell().getCellPathResolver();
    for (String fullyQualifiedBuildTarget : buildTargets) {
      BuildTarget target =
          BuildTargetParser.INSTANCE.parse(
              fullyQualifiedBuildTarget,
              BuildTargetPatternParser.fullyQualified(),
              distBuildCellPathResolver);
      targets.add(target);
    }

    return targets;
  }

  private DistBuildTargetGraphCodec createGraphCodec() {
    TypeCoercerFactory typeCoercerFactory =
        new DefaultTypeCoercerFactory(PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY);
    ParserTargetNodeFactory<TargetNode<?, ?>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory));

    DistBuildTargetGraphCodec targetGraphCodec =
        new DistBuildTargetGraphCodec(
            parserTargetNodeFactory,
            new Function<TargetNode<?, ?>, Map<String, Object>>() {
              @Nullable
              @Override
              public Map<String, Object> apply(TargetNode<?, ?> input) {
                try {
                  return args.getParser()
                      .getRawTargetNode(
                          args.getBuckEventBus(),
                          args.getRootCell().getCell(input.getBuildTarget()),
                          /* enableProfiling */ false,
                          args.getExecutorService(),
                          input);
                } catch (BuildFileParseException e) {
                  throw new RuntimeException(e);
                }
              }
            },
            new HashSet<>(args.getState().getRemoteState().getTopLevelTargets()));

    return targetGraphCodec;
  }

  private class LocalBuilderImpl implements LocalBuilder {
    private final BuckConfig distBuildConfig;
    private final CachingBuildEngineBuckConfig engineConfig;

    public LocalBuilderImpl() {
      this.distBuildConfig = args.getRemoteRootCellConfig();
      this.engineConfig = distBuildConfig.getView(CachingBuildEngineBuckConfig.class);
    }

    @Override
    public int buildLocallyAndReturnExitCode(Iterable<String> targetsToBuild)
        throws IOException, InterruptedException {
      // TODO(ruibm): Fix this to work with Android.
      MetadataChecker.checkAndCleanIfNeeded(args.getRootCell());
      try (CachingBuildEngine buildEngine =
              new CachingBuildEngine(
                  Preconditions.checkNotNull(cachingBuildEngineDelegate),
                  args.getExecutorService(),
                  args.getExecutorService(),
                  new DefaultStepRunner(),
                  engineConfig.getBuildEngineMode(),
                  engineConfig.getBuildMetadataStorage(),
                  engineConfig.getBuildDepFiles(),
                  engineConfig.getBuildMaxDepFileCacheEntries(),
                  engineConfig.getBuildArtifactCacheSizeLimit(),
                  Preconditions.checkNotNull(actionGraphAndResolver).getResolver(),
                  args.getBuildInfoStoreManager(),
                  engineConfig.getResourceAwareSchedulingInfo(),
                  RuleKeyFactories.of(
                      distBuildConfig.getKeySeed(),
                      cachingBuildEngineDelegate.getFileHashCache(),
                      actionGraphAndResolver.getResolver(),
                      engineConfig.getBuildInputRuleKeyFileSizeLimit(),
                      new DefaultRuleKeyCache<>()));
          Build build =
              new Build(
                  Preconditions.checkNotNull(actionGraphAndResolver).getActionGraph(),
                  Preconditions.checkNotNull(actionGraphAndResolver).getResolver(),
                  args.getRootCell(),
                  Optional.empty(),
                  getExplodingAndroidSupplier(),
                  buildEngine,
                  args.getArtifactCache(),
                  distBuildConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder(),
                  args.getConsole(),
                  /* defaultTestTimeoutMillis */ 1000,
                  /* isCodeCoverageEnabled */ false,
                  /* isInclNoLocationClassesEnabled */ false,
                  /* isDebugEnabled */ false,
                  /* shouldReportAbsolutePaths */ false,
                  RuleKeyDiagnosticsMode.NEVER,
                  args.getBuckEventBus(),
                  args.getPlatform(),
                  ImmutableMap.of(),
                  args.getClock(),
                  new ConcurrencyLimit(
                      4,
                      distBuildConfig.getResourceAllocationFairness(),
                      4,
                      distBuildConfig.getDefaultResourceAmounts(),
                      distBuildConfig.getMaximumResourceAmounts().withCpu(4)),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  new DefaultProcessExecutor(args.getConsole()),
                  args.getExecutors())) {

        return build.executeAndPrintFailuresToEventBus(
            fullyQualifiedNameToBuildTarget(targetsToBuild),
            /* isKeepGoing */ true,
            args.getBuckEventBus(),
            args.getConsole(),
            Optional.empty());
      }
    }
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
                return args.getState().createMaterializerAndPreload(cache, args.getProvider());
              } catch (IOException exception) {
                throw new RuntimeException(
                    String.format(
                        "Failed to create the Materializer for file system [%s]",
                        cache.getFilesystem()),
                    exception);
              }
            });

    return new StackedFileHashCaches(remoteStackedFileHashCache, materializingStackedFileHashCache);
  }
}
