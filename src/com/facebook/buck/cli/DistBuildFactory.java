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

package com.facebook.buck.cli;

import com.facebook.buck.artifact_cache.ArtifactCacheFactory;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildExecutorArgs;
import com.facebook.buck.distributed.DistBuildLogStateTracker;
import com.facebook.buck.distributed.DistBuildMode;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildSlaveExecutor;
import com.facebook.buck.distributed.DistBuildState;
import com.facebook.buck.distributed.FrontendService;
import com.facebook.buck.distributed.MultiSourceContentsProvider;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.slb.ClientSideSlb;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.ThriftOverHttpServiceConfig;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import okhttp3.OkHttpClient;

public abstract class DistBuildFactory {
  private DistBuildFactory() {
    // Do not instantiate.
  }

  public static DistBuildService newDistBuildService(CommandRunnerParams params) {
    return new DistBuildService(newFrontendService(params));
  }

  public static DistBuildLogStateTracker newDistBuildLogStateTracker(
      Path logDir, ProjectFilesystem fileSystem) {
    return new DistBuildLogStateTracker(logDir, fileSystem);
  }

  public static FrontendService newFrontendService(CommandRunnerParams params) {
    DistBuildConfig config = new DistBuildConfig(params.getBuckConfig());
    ClientSideSlb slb =
        config.getFrontendConfig().createClientSideSlb(params.getClock(), params.getBuckEventBus());
    OkHttpClient client = config.createOkHttpClient();

    return new FrontendService(
        ThriftOverHttpServiceConfig.of(
            new LoadBalancedService(slb, client, params.getBuckEventBus())));
  }

  public static DistBuildSlaveExecutor createDistBuildExecutor(
      BuildJobState jobState,
      CommandRunnerParams params,
      WeightedListeningExecutorService executorService,
      DistBuildService service,
      DistBuildMode mode,
      int coordinatorPort,
      Optional<StampedeId> stampedeId,
      Optional<Path> globalCacheDir)
      throws InterruptedException, IOException {
    DistBuildState state =
        DistBuildState.load(
            Optional.of(params.getBuckConfig()),
            jobState,
            params.getCell(),
            params.getKnownBuildRuleTypesFactory());

    Preconditions.checkArgument(state.getCells().size() > 0);

    // Create a cache factory which uses a combination of the distributed build config,
    // overridden with the local buck config (i.e. the build slave).
    Cell rootCell = Preconditions.checkNotNull(state.getCells().get(0));
    ArtifactCacheFactory distBuildArtifactCacheFactory =
        params.getArtifactCacheFactory().cloneWith(rootCell.getBuckConfig());

    DistBuildSlaveExecutor executor =
        new DistBuildSlaveExecutor(
            DistBuildExecutorArgs.builder()
                .setBuckEventBus(params.getBuckEventBus())
                .setPlatform(params.getPlatform())
                .setClock(params.getClock())
                .setArtifactCache(distBuildArtifactCacheFactory.newInstance(true))
                .setState(state)
                .setRootCell(params.getCell())
                .setParser(params.getParser())
                .setExecutorService(executorService)
                .setActionGraphCache(params.getActionGraphCache())
                .setCacheKeySeed(params.getBuckConfig().getKeySeed())
                .setConsole(params.getConsole())
                .setProvider(new MultiSourceContentsProvider(service, globalCacheDir))
                .setExecutors(params.getExecutors())
                .setDistBuildMode(mode)
                .setCoordinatorPort(coordinatorPort)
                .setStampedeId(stampedeId.orElse(new StampedeId().setId("LOCAL_FILE")))
                .setVersionedTargetGraphCache(params.getVersionedTargetGraphCache())
                .setBuildInfoStoreManager(params.getBuildInfoStoreManager())
                .build());
    return executor;
  }
}
