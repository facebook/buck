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
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.util.LocalContentAddressedStorage;
import com.facebook.buck.util.NamedTemporaryDirectory;
import com.google.common.io.Closer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.Optional;

/** Factory for creating grpc-based strategies. */
public class GrpcExecutionFactory {
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
        "in-process", channel, channel, Optional.empty(), buckEventBus) {
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
      Optional<String> traceID,
      BuckEventBus buckEventBus)
      throws IOException {
    ManagedChannel executionEngineChannel =
        ManagedChannelBuilder.forAddress(executionEngineHost, executionEnginePort)
            .usePlaintext(true)
            .maxInboundMessageSize(500 * 1024 * 1024)
            .build();

    ManagedChannel casChannel =
        ManagedChannelBuilder.forAddress(casHost, casPort)
            .usePlaintext(true)
            .maxInboundMessageSize(500 * 1024 * 1024)
            .build();

    return new GrpcRemoteExecutionClients(
        "buck", executionEngineChannel, casChannel, traceID, buckEventBus);
  }
}
