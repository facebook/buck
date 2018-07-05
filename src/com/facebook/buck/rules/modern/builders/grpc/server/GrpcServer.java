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

package com.facebook.buck.rules.modern.builders.grpc.server;

import com.facebook.buck.rules.modern.builders.LocalContentAddressedStorage;
import com.facebook.buck.rules.modern.builders.grpc.GrpcRemoteExecution;
import com.facebook.buck.rules.modern.builders.grpc.GrpcRemoteExecutionServiceImpl;
import com.facebook.buck.util.NamedTemporaryDirectory;
import com.google.common.io.Closer;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.ChannelOption;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** A simple remote execution server. */
public class GrpcServer implements Closeable {
  private final Server server;
  private final NamedTemporaryDirectory workDir;

  public GrpcServer(int port) throws IOException {
    workDir = new NamedTemporaryDirectory("__remote__");
    GrpcRemoteExecutionServiceImpl remoteExecution =
        new GrpcRemoteExecutionServiceImpl(
            new LocalContentAddressedStorage(
                workDir.getPath().resolve("__cache__"), GrpcRemoteExecution.PROTOCOL),
            workDir.getPath().resolve("__work__"));
    NettyServerBuilder builder = NettyServerBuilder.forPort(port);

    builder.maxMessageSize(500 * 1024 * 1024);
    builder.withChildOption(ChannelOption.SO_REUSEADDR, true);
    remoteExecution.getServices().forEach(builder::addService);
    this.server = builder.build().start();
  }

  public void awaitTermination() throws InterruptedException {
    server.awaitTermination();
  }

  @Override
  public void close() throws IOException {
    try (Closer closer = Closer.create()) {
      closer.register(server::shutdown);
      closer.register(workDir);
    }
    try {
      server.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
