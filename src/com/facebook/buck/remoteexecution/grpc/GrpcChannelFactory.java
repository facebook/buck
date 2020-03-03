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

import com.facebook.buck.remoteexecution.config.RemoteExecutionStrategyConfig;
import com.facebook.buck.remoteexecution.grpc.retry.Backoff;
import com.facebook.buck.remoteexecution.grpc.retry.RetryClientInterceptor;
import com.facebook.buck.remoteexecution.grpc.retry.RetryPolicy;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/** Factory class for creating GRPC channels. */
public class GrpcChannelFactory {
  private static final int MAX_INBOUND_MESSAGE_SIZE = 500 * 1024 * 1024;
  private static final int MAX_CONNECT_RETRIES = 2;
  private static final int INITIAL_DELAY_ON_RETRY_MS = 50;
  private static final int MAX_DELAY_ON_RETRY_MS = 2000;
  private static final int KEEPALIVE_TIMEOUT_S = 10;

  private static NettyChannelBuilder channelBuilder(
      String host, int port, RemoteExecutionStrategyConfig strategyConfig) {
    return NettyChannelBuilder.forAddress(host, port)
        .maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE)
        .keepAliveTime(strategyConfig.getGrpcKeepAlivePeriodSeconds(), TimeUnit.SECONDS)
        .keepAliveTimeout(KEEPALIVE_TIMEOUT_S, TimeUnit.SECONDS)
        .intercept(getRetryInterceptor());
  }

  private static RetryClientInterceptor getRetryInterceptor() {
    return new RetryClientInterceptor(
        RetryPolicy.builder()
            .setMaxRetries(MAX_CONNECT_RETRIES)
            .setBackoffStrategy(
                Backoff.exponential(INITIAL_DELAY_ON_RETRY_MS, MAX_DELAY_ON_RETRY_MS))
            // Build Actions and CAS operations can be retried without issue even if they fail in
            // the middle of execution.
            .setRestartAllStreamingCalls(true)
            .build());
  }

  public static NettyChannelBuilder createInsecureChannel(
      String host, int port, RemoteExecutionStrategyConfig strategyConfig) {
    return channelBuilder(host, port, strategyConfig).usePlaintext();
  }

  /**
   * Creates a secure GRPC channel for use by a service stub.
   *
   * @param host is the GRPC server's host
   * @param port is the GRPC server's port
   * @param certPath is a path to a client cert
   * @param keyPath is a path to a client key
   * @param caPath is a path to an additional CA file
   * @param strategyConfig is a remote execution strategy config
   * @return the channel builder
   * @throws SSLException if the SSL context cannot be created
   */
  public static NettyChannelBuilder createSecureChannel(
      String host,
      int port,
      Optional<Path> certPath,
      Optional<Path> keyPath,
      Optional<Path> caPath,
      RemoteExecutionStrategyConfig strategyConfig)
      throws SSLException {

    SslContextBuilder contextBuilder = GrpcSslContexts.forClient();
    if (certPath.isPresent() && keyPath.isPresent()) {
      contextBuilder.keyManager(certPath.get().toFile(), keyPath.get().toFile());
    }
    caPath.ifPresent(path -> contextBuilder.trustManager(path.toFile()));

    return channelBuilder(host, port, strategyConfig)
        .sslContext(contextBuilder.build())
        .negotiationType(NegotiationType.TLS);
  }
}
