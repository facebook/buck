/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.grpc;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.MetadataProviderFactory;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.util.LocalContentAddressedStorage;
import com.facebook.buck.util.NamedTemporaryDirectory;
import com.google.common.io.Closer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.net.ssl.SSLException;

/** Factory for creating grpc-based strategies. */
public class GrpcExecutionFactory {
  public static final int MAX_INBOUND_MESSAGE_SIZE = 500 * 1024 * 1024;
  /**
   * The in-process strategy starts up a grpc remote execution service in process and connects to it
   * directly.
   */
  public static RemoteExecutionClients createInProcess(BuckEventBus buckEventBus)
      throws IOException {
    NamedTemporaryDirectory workDir = new NamedTemporaryDirectory("__remote__");
    GrpcRemoteExecutionServiceImpl remoteExecution =
        new GrpcRemoteExecutionServiceImpl(
            new LocalContentAddressedStorage(
                workDir.getPath().resolve("__cache__"), GrpcRemoteExecutionClients.PROTOCOL),
            workDir.getPath().resolve("__work__"));

    InProcessServerBuilder builder = InProcessServerBuilder.forName("unique");
    remoteExecution.getServices().forEach(builder::addService);
    Server server = builder.build().start();
    ManagedChannel channel = InProcessChannelBuilder.forName("unique").build();

    return new GrpcRemoteExecutionClients(
        "in-process",
        channel,
        channel,
        MetadataProviderFactory.emptyMetadataProvider(),
        buckEventBus) {
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
      boolean insecure,
      boolean casInsecure,
      Optional<Path> certPath,
      Optional<Path> keyPath,
      Optional<Path> caPath,
      MetadataProvider metadataProvider,
      BuckEventBus buckEventBus)
      throws SSLException {

    ManagedChannel executionEngineChannel;
    if (insecure) {
      executionEngineChannel = createInsecureChannel(executionEngineHost, executionEnginePort);
    } else {
      executionEngineChannel =
          createSecureChannel(executionEngineHost, executionEnginePort, certPath, keyPath, caPath);
    }

    ManagedChannel casChannel;
    if (casInsecure) {
      casChannel = createInsecureChannel(casHost, casPort);
    } else {
      casChannel = createSecureChannel(casHost, casPort, certPath, keyPath, caPath);
    }

    return new GrpcRemoteExecutionClients(
        "buck", executionEngineChannel, casChannel, metadataProvider, buckEventBus);
  }

  private static ManagedChannel createInsecureChannel(String host, int port) {
    return ManagedChannelBuilder.forAddress(host, port)
        .maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE)
        .usePlaintext(true)
        .build();
  }

  private static ManagedChannel createSecureChannel(
      String host, int port, Optional<Path> certPath, Optional<Path> keyPath, Optional<Path> caPath)
      throws SSLException {

    SslContextBuilder contextBuilder = GrpcSslContexts.forClient();
    if (certPath.isPresent() && keyPath.isPresent()) {
      contextBuilder.keyManager(certPath.get().toFile(), keyPath.get().toFile());
    }
    if (caPath.isPresent()) {
      contextBuilder.trustManager(caPath.get().toFile());
    }

    return NettyChannelBuilder.forAddress(host, port)
        .maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE)
        .sslContext(contextBuilder.build())
        .negotiationType(NegotiationType.TLS)
        .build();
  }
}
