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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.MetadataProviderFactory;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.config.RemoteExecutionStrategyConfig;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.util.LocalContentAddressedStorage;
import com.facebook.buck.util.NamedTemporaryDirectory;
import com.google.common.io.Closer;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.net.ssl.SSLException;

/** Factory for creating grpc-based strategies. */
public class GrpcExecutionFactory {
  private static final int CAS_DEADLINE_S = 120;

  /**
   * The in-process strategy starts up a grpc remote execution service in process and connects to it
   * directly.
   */
  public static RemoteExecutionClients createInProcess(
      BuckEventBus buckEventBus, RemoteExecutionStrategyConfig strategyConfig) throws IOException {
    NamedTemporaryDirectory workDir = new NamedTemporaryDirectory("__remote__");
    GrpcRemoteExecutionServiceServer remoteExecution =
        new GrpcRemoteExecutionServiceServer(
            new LocalContentAddressedStorage(
                workDir.getPath().resolve("__cache__"),
                GrpcRemoteExecutionClients.PROTOCOL,
                buckEventBus),
            workDir.getPath().resolve("__work__"));

    InProcessServerBuilder builder = InProcessServerBuilder.forName("unique");
    remoteExecution.getServices().forEach(builder::addService);
    Server server = builder.build().start();
    ManagedChannel channel = InProcessChannelBuilder.forName("unique").build();

    return new GrpcRemoteExecutionClients(
        "in-process",
        channel,
        channel,
        CAS_DEADLINE_S,
        MetadataProviderFactory.emptyMetadataProvider(),
        buckEventBus,
        strategyConfig) {
      @Override
      public void close() throws IOException {
        try (Closer closer = Closer.create()) {
          closer.register(server::shutdown);
          closer.register(workDir);
          closer.register(super::close);
        }
        try {
          server.awaitTermination();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  /** The remote strategy connects to a remote grpc remote execution service. */
  public static RemoteExecutionClients createRemote(
      String executionEngineHost,
      int executionEnginePort,
      String casHost,
      int casPort,
      int casDeadline,
      boolean insecure,
      boolean casInsecure,
      Optional<Path> certPath,
      Optional<Path> keyPath,
      Optional<Path> caPath,
      RemoteExecutionStrategyConfig strategyConfig,
      MetadataProvider metadataProvider,
      BuckEventBus buckEventBus)
      throws SSLException {

    NettyChannelBuilder executionEngineChannel;
    if (insecure) {
      executionEngineChannel =
          GrpcChannelFactory.createInsecureChannel(
              executionEngineHost, executionEnginePort, strategyConfig);
    } else {
      executionEngineChannel =
          GrpcChannelFactory.createSecureChannel(
              executionEngineHost, executionEnginePort, certPath, keyPath, caPath, strategyConfig);
    }

    NettyChannelBuilder casChannelBuilder;
    if (casInsecure) {
      casChannelBuilder =
          GrpcChannelFactory.createInsecureChannel(casHost, casPort, strategyConfig);
    } else {
      casChannelBuilder =
          GrpcChannelFactory.createSecureChannel(
              casHost, casPort, certPath, keyPath, caPath, strategyConfig);
    }
    casChannelBuilder.flowControlWindow(100 * 1024 * 1024);

    return new GrpcRemoteExecutionClients(
        "buck",
        executionEngineChannel,
        strategyConfig.getNumEngineConnections(),
        casChannelBuilder.build(),
        casDeadline,
        metadataProvider,
        buckEventBus,
        strategyConfig);
  }
}
