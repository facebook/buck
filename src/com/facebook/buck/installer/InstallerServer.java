/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.installer;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.facebook.buck.util.types.Unit;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.Epoll;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD

public class InstallerServer {

  public InstallerServer(
      String unixDomainSocket, InstallCommand installer, Logger logger, int tcpPort)
      throws IOException, InterruptedException {
    SettableFuture<Unit> isDone = SettableFuture.create();
    InstallerService installerService = new InstallerService(installer, isDone, logger);
    Server grpcServer = buildServer(installerService, unixDomainSocket, logger, tcpPort);
    isDone.addCallback(
        new FutureCallback<>() {
          @Override
          public void onSuccess(Unit unit) {
            logger.info("Installer Server shutting down...");
            stopServer(grpcServer);
          }

          @Override
          public void onFailure(Throwable t) {
            logger.log(Level.WARNING, "Execution exception...", t);
          }
        },
        directExecutor());

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                  System.err.println("*** shutting down gRPC server since JVM is shutting down");
                  stopServer(grpcServer);
                  System.err.println("*** server shut down");
                }));

    grpcServer.start();
    grpcServer.awaitTermination();
  }

  /** Build an installer server at requested {@code unixDomainSocket} or fallback to TCP. */
  private Server buildServer(
      InstallerService service, String unixDomainSocket, Logger logger, int tcpPort) {
    logger.info(
        String.format(
            "Starting Installer Server using UDS: %s or TCP: %s", unixDomainSocket, tcpPort));
    /// We can use UDS if Epoll available
    if (Epoll.isAvailable()) {
      return getUDSServer(service, unixDomainSocket, logger);
    }
    return getTCPServer(service, logger, tcpPort);
  }

  private Server getUDSServer(InstallerService service, String unixDomainSocket, Logger logger) {
    EventLoopGroup group = new EpollEventLoopGroup();
    Server server =
        NettyServerBuilder.forAddress(new DomainSocketAddress(unixDomainSocket))
            .channelType(EpollServerDomainSocketChannel.class)
            .workerEventLoopGroup(group)
            .bossEventLoopGroup(group)
            .addService(service)
            .build();
    logger.info(String.format("Starting server listening on UDS %s", unixDomainSocket));
    return server;
  }

  private Server getTCPServer(InstallerService service, Logger logger, int tcpPort) {
    EventLoopGroup group = new NioEventLoopGroup();
    Server server =
        NettyServerBuilder.forPort(tcpPort)
            .channelType(NioServerSocketChannel.class)
            .workerEventLoopGroup(group)
            .bossEventLoopGroup(group)
            .addService(service)
            .build();
    logger.info(String.format("Starting server listening on TCP port %s", tcpPort));
    return server;
  }

  /** Shuts down installer server. */
  private void stopServer(Server grpcServer) {
    if (grpcServer != null && !grpcServer.isTerminated()) {
      try {
        grpcServer.shutdown().awaitTermination();
        if (!grpcServer.isTerminated()) {
          grpcServer.shutdownNow();
        }
      } catch (InterruptedException e) {
        grpcServer.shutdownNow();
      }
    }
  }
}
