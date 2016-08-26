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
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildEngine;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nullable;

public class DistBuildExecutor {
  private final DistBuildExecutorArgs args;

  @Nullable
  private TargetGraph targetGraph;

  @Nullable
  private ActionGraphAndResolver actionGraphAndResolver;

  @Nullable
  private DistributedCachingBuildEngineDelegate cachingBuildEngineDelegate;

  public DistBuildExecutor(DistBuildExecutorArgs args) {
    this.args = args;
  }

  public int buildAndReturnExitCode() throws IOException, InterruptedException {
    createBuildEngineDelegate();
    BuckConfig config = args.getRemoteRootCellConfig();
    BuildEngine buildEngine = new CachingBuildEngine(
        Preconditions.checkNotNull(cachingBuildEngineDelegate),
        args.getExecutorService(),
        config.getBuildEngineMode(),
        config.getBuildDepFiles(),
        config.getBuildMaxDepFileCacheEntries(),
        config.getBuildArtifactCacheSizeLimit(),
        config.getBuildInputRuleKeyFileSizeLimit(),
        args.getObjectMapper(),
        Preconditions.checkNotNull(actionGraphAndResolver).getResolver(),
        config.getKeySeed());

    // TODO(ruibm): Fix this to work with Android.
    try (Build build = new Build(
        Preconditions.checkNotNull(actionGraphAndResolver).getActionGraph(),
        Preconditions.checkNotNull(actionGraphAndResolver).getResolver(),
        args.getRootCell(),
        Optional.<TargetDevice>absent(),
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
        ImmutableMap.<String, String>of(),
        args.getObjectMapper(),
        args.getClock(),
        new ConcurrencyLimit(4, 1),
        Optional.<AdbOptions>absent(),
        Optional.<TargetDeviceOptions>absent(),
        args.getExecutors())) {

      // TODO(ruibm): We need to pass to the distbuild target via de distributed build
      //              thrift structs.
      FluentIterable<BuildTarget> allTargets = FluentIterable.from(
          Preconditions.checkNotNull(targetGraph).getNodes())
          .transform(new Function<TargetNode<?>, BuildTarget>() {
            @Override
            public BuildTarget apply(TargetNode<?> input) {
              return input.getBuildTarget();
            }
          });

      return build.executeAndPrintFailuresToEventBus(
          allTargets,
          /* isKeepGoing */ true,
          args.getBuckEventBus(),
          args.getConsole(),
          Optional.<Path>absent());
    }
  }

  private TargetGraph createTargetGraph() throws IOException, InterruptedException {
    if (targetGraph != null) {
      return targetGraph;
    }

    DistributedBuildTargetGraphCodec codec = createGraphCodec();
    targetGraph = Preconditions.checkNotNull(codec.createTargetGraph(
        args.getState().getRemoteState().getTargetGraph(),
        Functions.forMap(args.getState().getCells())));
    return targetGraph;
  }

  private ActionGraphAndResolver createActionGraphAndResolver()
      throws IOException, InterruptedException {
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

  private DistributedCachingBuildEngineDelegate createBuildEngineDelegate()
      throws IOException, InterruptedException {
    if (cachingBuildEngineDelegate != null) {
      return cachingBuildEngineDelegate;
    }

    createActionGraphAndResolver();
    cachingBuildEngineDelegate =
        new DistributedCachingBuildEngineDelegate(
            new SourcePathResolver(
                Preconditions.checkNotNull(actionGraphAndResolver).getResolver()),
            args.getState(),
            args.getProvider());
    return cachingBuildEngineDelegate;
  }

  private Supplier<AndroidPlatformTarget> getExplodingAndroidSupplier() {
    return AndroidPlatformTarget.EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER;
  }

  private DistributedBuildTargetGraphCodec createGraphCodec() {
    DistributedBuildTypeCoercerFactory typeCoercerFactory =
        new DistributedBuildTypeCoercerFactory(args.getObjectMapper());
    ParserTargetNodeFactory<TargetNode<?>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory));

    DistributedBuildTargetGraphCodec targetGraphCodec = new DistributedBuildTargetGraphCodec(
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
            } catch (BuildFileParseException | InterruptedException e) {
              throw new RuntimeException(e);
            }
          }
        });

    return targetGraphCodec;
  }
}
