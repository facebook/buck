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

import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildExecutor;
import com.facebook.buck.distributed.DistBuildExecutorArgs;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistributedBuildState;
import com.facebook.buck.distributed.FileContentsProviders;
import com.facebook.buck.distributed.FrontendService;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.slb.ClientSideSlb;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.ThriftOverHttpServiceConfig;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;

import java.io.IOException;

import okhttp3.OkHttpClient;

public abstract class DistBuildFactory {
  private DistBuildFactory() {
    // Do not instantiate.
  }

  public static DistBuildService newDistBuildService(CommandRunnerParams params) {
    return new DistBuildService(newFrontendService(params));
  }

  public static FrontendService newFrontendService(
      CommandRunnerParams params) {
    DistBuildConfig config = new DistBuildConfig(params.getBuckConfig());
    ClientSideSlb slb = config.getFrontendConfig().createHttpClientSideSlb(
        params.getClock(),
        params.getBuckEventBus(),
        new CommandThreadFactory("DistBuildService"));
    OkHttpClient client = config.createOkHttpClient();

    return new FrontendService(
        ThriftOverHttpServiceConfig.of(new LoadBalancedService(
            slb,
            client,
            params.getBuckEventBus())));
  }

  public static DistBuildExecutor createDistBuildExecutor(
      BuildJobState jobState,
      CommandRunnerParams params,
      WeightedListeningExecutorService executorService,
      DistBuildService service) throws IOException, InterruptedException {
    DistributedBuildState state = DistributedBuildState.load(jobState, params.getCell());
    DistBuildExecutor executor = new DistBuildExecutor(
        DistBuildExecutorArgs.builder()
            .setBuckEventBus(params.getBuckEventBus())
            .setPlatform(params.getPlatform())
            .setClock(params.getClock())
            .setArtifactCache(params.getArtifactCache())
            .setState(state)
            .setObjectMapper(params.getObjectMapper())
            .setRootCell(params.getCell())
            .setParser(params.getParser())
            .setExecutorService(executorService)
            .setActionGraphCache(params.getActionGraphCache())
            .setCacheKeySeed(params.getBuckConfig().getKeySeed())
            .setConsole(params.getConsole())
            .setProvider(FileContentsProviders.createDefaultProvider(service))
            .build());
    return executor;
  }
}
