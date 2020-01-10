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

package com.facebook.buck.remoteexecution.grpc;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient;
import com.facebook.buck.remoteexecution.MetadataProviderFactory;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.util.timing.DefaultClock;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class TestRemoteExecutionClients implements RemoteExecutionClients {
  private BuckEventBus eventBus;
  private Server server;

  private RemoteExecutionClients clients;

  public TestRemoteExecutionClients(List<BindableService> services) throws IOException {
    eventBus = new DefaultBuckEventBus(new DefaultClock(), new BuildId("dontcare"));
    String serverName = "uniquish-" + new Random().nextLong();

    InProcessServerBuilder serverBuilder =
        InProcessServerBuilder.forName(serverName).directExecutor();
    for (BindableService service : services) {
      serverBuilder.addService(service);
    }

    server = serverBuilder.build().start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

    clients =
        new GrpcRemoteExecutionClients(
            "buck",
            channel,
            channel,
            100,
            MetadataProviderFactory.emptyMetadataProvider(),
            eventBus,
            FakeBuckConfig.builder()
                .build()
                .getView(RemoteExecutionConfig.class)
                .getStrategyConfig());
  }

  @Override
  public RemoteExecutionServiceClient getRemoteExecutionService() {
    return clients.getRemoteExecutionService();
  }

  @Override
  public ContentAddressedStorageClient getContentAddressedStorage() {
    return clients.getContentAddressedStorage();
  }

  @Override
  public Protocol getProtocol() {
    return clients.getProtocol();
  }

  @Override
  public void close() throws IOException {
    try {
      server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    clients.close();
  }
}
