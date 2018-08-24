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
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphConfig;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphFactory;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProvider;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildMode;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildState;
import com.facebook.buck.distributed.FileContentsProvider;
import com.facebook.buck.distributed.FileMaterializationStatsTracker;
import com.facebook.buck.distributed.FrontendService;
import com.facebook.buck.distributed.MultiSourceContentsProvider;
import com.facebook.buck.distributed.ServerContentsProvider;
import com.facebook.buck.distributed.build_client.LogStateTracker;
import com.facebook.buck.distributed.build_slave.BuildSlaveService;
import com.facebook.buck.distributed.build_slave.BuildSlaveTimingStatsTracker;
import com.facebook.buck.distributed.build_slave.CapacityService;
import com.facebook.buck.distributed.build_slave.CoordinatorBuildRuleEventsPublisher;
import com.facebook.buck.distributed.build_slave.DistBuildSlaveExecutor;
import com.facebook.buck.distributed.build_slave.DistBuildSlaveExecutorArgs;
import com.facebook.buck.distributed.build_slave.HealthCheckStatsTracker;
import com.facebook.buck.distributed.build_slave.MinionBuildProgressTracker;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.impl.ConfigRuleKeyConfigurationFactory;
import com.facebook.buck.slb.ClientSideSlb;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.RetryingHttpService;
import com.facebook.buck.slb.SingleUriService;
import com.facebook.buck.slb.ThriftOverHttpServiceConfig;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import okhttp3.OkHttpClient;

public abstract class DistBuildFactory {
  private DistBuildFactory() {
    // Do not instantiate.
  }

  public static DistBuildService newDistBuildService(CommandRunnerParams params) {
    return new DistBuildService(
        newFrontendService(params), params.getBuildEnvironmentDescription().getUser());
  }

  public static LogStateTracker newDistBuildLogStateTracker(
      Path logDir, ProjectFilesystem fileSystem, DistBuildService service) {
    return new LogStateTracker(logDir, fileSystem, service);
  }

  public static FrontendService newFrontendService(CommandRunnerParams params) {
    DistBuildConfig config = new DistBuildConfig(params.getBuckConfig());
    ClientSideSlb slb =
        config.getFrontendConfig().createClientSideSlb(params.getClock(), params.getBuckEventBus());
    OkHttpClient client = config.createOkHttpClient();

    LoadBalancedService loadBalanceService =
        new LoadBalancedService(slb, client, params.getBuckEventBus());
    RetryingHttpService httpService =
        new RetryingHttpService(
            params.getBuckEventBus(),
            loadBalanceService,
            "buck_frontend_http_retries",
            config.getFrontendRequestMaxRetries(),
            config.getFrontendRequestRetryIntervalMillis());

    return new FrontendService(ThriftOverHttpServiceConfig.of(httpService));
  }

  public static CapacityService newCapacityService(
      BuildSlaveService buildSlaveService, BuildSlaveRunId buildSlaveRunId) {
    return new CapacityService(buildSlaveService, buildSlaveRunId);
  }

  public static BuildSlaveService newBuildSlaveService(CommandRunnerParams params) {
    DistBuildConfig config = new DistBuildConfig(params.getBuckConfig());

    RetryingHttpService retryingHttpService = null;
    try {
      HttpService httpService =
          new SingleUriService(
              new URI(String.format("http://localhost:%d", config.getBuildSlaveHttpPort())),
              new OkHttpClient.Builder().build());

      retryingHttpService =
          new RetryingHttpService(
              params.getBuckEventBus(),
              httpService,
              "build_slave_http_retries",
              config.getBuildSlaveRequestMaxRetries(),
              config.getBuildSlaveRequestRetryIntervalMillis());

    } catch (URISyntaxException e) {
      throw new RuntimeException(
          String.format("Could not create BuildSlaveService: %s", e.getMessage()));
    }

    return new BuildSlaveService(ThriftOverHttpServiceConfig.of(retryingHttpService));
  }

  public static FileContentsProvider createMultiSourceContentsProvider(
      DistBuildService service,
      DistBuildConfig distBuildConfig,
      FileMaterializationStatsTracker fileMaterializationStatsTracker,
      ScheduledExecutorService sourceFileMultiFetchScheduler,
      ListeningExecutorService executorService,
      ProjectFilesystemFactory projectFilesystemFactory,
      Optional<Path> globalCacheDir)
      throws IOException, InterruptedException {
    return new MultiSourceContentsProvider(
        new ServerContentsProvider(
            service,
            sourceFileMultiFetchScheduler,
            executorService,
            fileMaterializationStatsTracker,
            distBuildConfig.getSourceFileMultiFetchBufferPeriodMs(),
            distBuildConfig.getSourceFileMultiFetchMaxBufferSize()),
        fileMaterializationStatsTracker,
        executorService,
        projectFilesystemFactory,
        globalCacheDir);
  }

  public static DistBuildSlaveExecutor createDistBuildExecutor(
      DistBuildState state,
      CommandRunnerParams params,
      WeightedListeningExecutorService executorService,
      DistBuildService service,
      DistBuildMode mode,
      int coordinatorPort,
      String coordinatorAddress,
      Optional<StampedeId> stampedeId,
      CapacityService capacityService,
      BuildSlaveRunId buildSlaveRunId,
      FileContentsProvider fileContentsProvider,
      HealthCheckStatsTracker healthCheckStatsTracker,
      BuildSlaveTimingStatsTracker timingStatsTracker,
      CoordinatorBuildRuleEventsPublisher coordinatorBuildRuleEventsPublisher,
      MinionBuildProgressTracker minionBuildProgressTracker,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope) {
    Preconditions.checkArgument(state.getCells().size() > 0);

    // Create a cache factory which uses a combination of the distributed build config,
    // overridden with the local buck config (i.e. the build slave).
    ArtifactCacheFactory distBuildArtifactCacheFactory =
        params.getArtifactCacheFactory().cloneWith(state.getRemoteRootCellConfig());

    RuleKeyConfiguration ruleKeyConfiguration =
        ConfigRuleKeyConfigurationFactory.create(
            state.getRemoteRootCellConfig(), params.getBuckModuleManager());

    return new DistBuildSlaveExecutor(
        DistBuildSlaveExecutorArgs.builder()
            .setBuckEventBus(params.getBuckEventBus())
            .setPlatform(params.getPlatform())
            .setClock(params.getClock())
            .setArtifactCacheFactory(distBuildArtifactCacheFactory)
            .setState(state)
            .setParser(params.getParser())
            .setExecutorService(executorService)
            .setActionGraphProvider(
                new ActionGraphProvider(
                    params.getBuckEventBus(),
                    ActionGraphFactory.create(
                        params.getBuckEventBus(),
                        state.getRootCell().getCellProvider(),
                        params.getPoolSupplier(),
                        state.getRemoteRootCellConfig()),
                    new ActionGraphCache(
                        state.getRemoteRootCellConfig().getMaxActionGraphCacheEntries()),
                    ruleKeyConfiguration,
                    false,
                    false,
                    state
                        .getRemoteRootCellConfig()
                        .getView(ActionGraphConfig.class)
                        .getIncrementalActionGraphMode()))
            .setRuleKeyConfiguration(ruleKeyConfiguration)
            .setConsole(params.getConsole())
            .setLogDirectoryPath(params.getInvocationInfo().get().getLogDirectoryPath())
            .setProvider(fileContentsProvider)
            .setExecutors(params.getExecutors())
            .setDistBuildMode(mode)
            .setRemoteCoordinatorPort(coordinatorPort)
            .setRemoteCoordinatorAddress(coordinatorAddress)
            .setStampedeId(stampedeId.orElse(new StampedeId().setId("LOCAL_FILE")))
            .setCapacityService(capacityService)
            .setBuildSlaveRunId(buildSlaveRunId)
            .setVersionedTargetGraphCache(params.getVersionedTargetGraphCache())
            .setBuildInfoStoreManager(params.getBuildInfoStoreManager())
            .setDistBuildService(service)
            .setProjectFilesystemFactory(params.getProjectFilesystemFactory())
            .setTimingStatsTracker(timingStatsTracker)
            .setKnownRuleTypesProvider(params.getKnownRuleTypesProvider())
            .setCoordinatorBuildRuleEventsPublisher(coordinatorBuildRuleEventsPublisher)
            .setMinionBuildProgressTracker(minionBuildProgressTracker)
            .setHealthCheckStatsTracker(healthCheckStatsTracker)
            .setRuleKeyCacheScope(ruleKeyCacheScope)
            .setRemoteCommand(state.getRemoteState().getCommand())
            .build());
  }
}
