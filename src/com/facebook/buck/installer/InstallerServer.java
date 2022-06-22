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
import java.util.concurrent.ExecutionException;

public class InstallerServer {
  private final Server server;
  private final InstallerService service;
  static int DEFAULT_PORT = 50055;
  public boolean uds = false;

  public InstallerServer(String unix_domain_socket, InstallType installer)
      throws IOException, RuntimeException, InterruptedException {
    this.service = new InstallerService(installer);
    this.server = buildServer(unix_domain_socket);
    new Thread(
            () -> {
              try {
                if (this.service.installFinished.get()) {
                  System.out.println("Installer Server shutting down...");
                }
              } catch (InterruptedException e) {
                System.out.printf("Interrupted...", e);
                Thread.currentThread().interrupt();
              } catch (ExecutionException e) {
                System.out.printf("Execution exception...", e);
              } finally {
                stopServer();
              }
            })
        .start();
    this.server.awaitTermination();
  }

  /**
   * Build an installer server at requested unix_domain_socket or fallback to TCP on 50055.
   *
   * @param unix_domain_socket unix_domain_socket
   */
  public Server buildServer(String unix_domain_socket) throws IOException, RuntimeException {
    /// We can use UDS if Epoll available
    Server server;
    if (Epoll.isAvailable()) {
      EventLoopGroup group = new EpollEventLoopGroup();
      server =
          NettyServerBuilder.forAddress(new DomainSocketAddress(unix_domain_socket))
              .channelType(EpollServerDomainSocketChannel.class)
              .workerEventLoopGroup(group)
              .bossEventLoopGroup(group)
              .addService(this.service)
              .build()
              .start();
      System.out.printf("Server Listening on uds %s", unix_domain_socket);

    } else {
      EventLoopGroup group = new NioEventLoopGroup();
      server =
          NettyServerBuilder.forPort(DEFAULT_PORT)
              .channelType(NioServerSocketChannel.class)
              .workerEventLoopGroup(group)
              .bossEventLoopGroup(group)
              .addService(this.service)
              .build()
              .start();
      System.out.printf("Server Listening on uds %s", 50055);
    }
    return server;
  }

  /** Shuts down installer server. */
  public void stopServer() {
    if (server != null && !server.isTerminated()) {
      try {
        server.shutdown().awaitTermination();
        if (!server.isTerminated()) {
          server.shutdownNow();
        }
      } catch (InterruptedException e) {
        server.shutdownNow();
      }
    }
  }
}
