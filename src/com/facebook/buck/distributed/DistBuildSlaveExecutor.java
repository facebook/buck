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
import com.facebook.buck.command.Build;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.CachingBuildEngineBuckConfig;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

public class DistBuildSlaveExecutor {
  private final DistBuildExecutorArgs args;

  @Nullable
  private TargetGraph targetGraph;

  @Nullable
  private ActionGraphAndResolver actionGraphAndResolver;

  @Nullable
  private DistBuildCachingEngineDelegate cachingBuildEngineDelegate;

  private static final Logger LOG = Logger.get(DistBuildSlaveExecutor.class);

  public DistBuildSlaveExecutor(DistBuildExecutorArgs args) {
    this.args = args;
  }

  public int buildAndReturnExitCode() throws IOException, InterruptedException {
    createBuildEngineDelegate();
    BuckConfig config = args.getRemoteRootCellConfig();
    CachingBuildEngineBuckConfig cachingBuildEngineBuckConfig =
        config.getView(CachingBuildEngineBuckConfig.class);
    BuildEngine buildEngine = new CachingBuildEngine(
        Preconditions.checkNotNull(cachingBuildEngineDelegate),
        args.getExecutorService(),
        new DefaultStepRunner(),
        cachingBuildEngineBuckConfig.getBuildEngineMode(),
        cachingBuildEngineBuckConfig.getBuildDepFiles(),
        cachingBuildEngineBuckConfig.getBuildMaxDepFileCacheEntries(),
        cachingBuildEngineBuckConfig.getBuildArtifactCacheSizeLimit(),
        cachingBuildEngineBuckConfig.getBuildInputRuleKeyFileSizeLimit(),
        args.getObjectMapper(),
        Preconditions.checkNotNull(actionGraphAndResolver).getResolver(),
        config.getKeySeed(),
        cachingBuildEngineBuckConfig.getResourceAwareSchedulingInfo());

    // TODO(ruibm): Fix this to work with Android.
    try (Build build = new Build(
        Preconditions.checkNotNull(actionGraphAndResolver).getActionGraph(),
        Preconditions.checkNotNull(actionGraphAndResolver).getResolver(),
        args.getRootCell(),
        Optional.empty(),
        getExplodingAndroidSupplier(),
        buildEngine,
        args.getArtifactCache(),
        config.createDefaultJavaPackageFinder(),
        args.getConsole(),
        /* defaultTestTimeoutMillis */ 1000,
        /* isCodeCoverageEnabled */ false,
        /* isDebugEnabled */ false,
        /* shouldReportAbsolutePaths */ false,
        args.getBuckEventBus(),
        args.getPlatform(),
        ImmutableMap.of(),
        args.getObjectMapper(),
        args.getClock(),
        new ConcurrencyLimit(
            4,
            1,
            config.getResourceAllocationFairness(),
            4,
            config.getDefaultResourceAmounts(),
            config.getMaximumResourceAmounts().withCpu(4)),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        args.getExecutors())) {

      // TODO(ruibm): We need to pass to the distbuild target via de distributed build
      //              thrift structs.
      FluentIterable<BuildTarget> allTargets = FluentIterable.from(
          Preconditions.checkNotNull(targetGraph).getNodes())
          .transform(TargetNode::getBuildTarget);

      return build.executeAndPrintFailuresToEventBus(
          allTargets,
          /* isKeepGoing */ true,
          args.getBuckEventBus(),
          args.getConsole(),
          Optional.empty());
    }
  }

  private TargetGraph createTargetGraph() throws IOException {
    if (targetGraph != null) {
      return targetGraph;
    }

    DistBuildTargetGraphCodec codec = createGraphCodec();
    targetGraph = Preconditions.checkNotNull(codec.createTargetGraph(
        args.getState().getRemoteState().getTargetGraph(),
        Functions.forMap(args.getState().getCells())));
    return targetGraph;
  }

  private ActionGraphAndResolver createActionGraphAndResolver() throws IOException {
    if (actionGraphAndResolver != null) {
      return actionGraphAndResolver;
    }
    createTargetGraph();

    actionGraphAndResolver = Preconditions.checkNotNull(
        args.getActionGraphCache().getActionGraph(
            args.getBuckEventBus(),
            /* checkActionGraphs */ false,
            Preconditions.checkNotNull(targetGraph),
            args.getCacheKeySeed()));
    return actionGraphAndResolver;
  }

  private DistBuildCachingEngineDelegate createBuildEngineDelegate() throws IOException {
    if (cachingBuildEngineDelegate != null) {
      return cachingBuildEngineDelegate;
    }

    LoadingCache<ProjectFilesystem, DistBuildFileMaterializer> fileHashLoaders =
        CacheBuilder.newBuilder().build(
            new CacheLoader<ProjectFilesystem, DistBuildFileMaterializer>() {
              @Override
              public DistBuildFileMaterializer load(ProjectFilesystem filesystem) throws Exception {
                return args.getState().createMaterializingLoader(filesystem, args.getProvider());
              }
            });

    // Create all symlinks and touch all other files.
    // TODO(alisdair04): remove this once action graph doesn't read from file system.
    for (Cell cell : args.getState().getCells().values()) {
      try {
        fileHashLoaders.get(cell.getFilesystem()).preloadAllFiles();
      } catch (ExecutionException e) {
        LOG.error(e);
        throw new RuntimeException(e);
      }
    }

    createActionGraphAndResolver();
    cachingBuildEngineDelegate =
        new DistBuildCachingEngineDelegate(
            new SourcePathResolver(
                Preconditions.checkNotNull(actionGraphAndResolver).getResolver()),
            args.getState(),
            fileHashLoaders);
    return cachingBuildEngineDelegate;
  }

  private Supplier<AndroidPlatformTarget> getExplodingAndroidSupplier() {
    return AndroidPlatformTarget.EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER;
  }

  private DistBuildTargetGraphCodec createGraphCodec() {
    DistBuildTypeCoercerFactory typeCoercerFactory =
        new DistBuildTypeCoercerFactory(args.getObjectMapper());
    ParserTargetNodeFactory<TargetNode<?>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory));

    DistBuildTargetGraphCodec targetGraphCodec = new DistBuildTargetGraphCodec(
        args.getObjectMapper(),
        parserTargetNodeFactory,
        new Function<TargetNode<?>, Map<String, Object>>() {
          @Nullable
          @Override
          public Map<String, Object> apply(TargetNode<?> input) {
            try {
              return args.getParser().getRawTargetNode(
                  args.getBuckEventBus(),
                  args.getRootCell().getCell(input.getBuildTarget()),
                      /* enableProfiling */ false,
                  args.getExecutorService(),
                  input);
            } catch (BuildFileParseException e) {
              throw new RuntimeException(e);
            }
          }
        });

    return targetGraphCodec;
  }
}
