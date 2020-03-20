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

package com.facebook.buck.logd.client;

import com.facebook.buck.logd.LogDaemonException;
import com.facebook.buck.logd.proto.CreateLogRequest;
import com.facebook.buck.logd.proto.CreateLogResponse;
import com.facebook.buck.logd.proto.LogType;
import com.facebook.buck.logd.proto.LogdServiceGrpc;
import com.facebook.buck.logd.proto.ShutdownRequest;
import com.google.common.annotations.VisibleForTesting;
import com.google.rpc.Status;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Given a host and port number, this client is used to make a connection and stream logs to logD
 * server
 */
public class LogdClient implements LogDaemonClient {
  private static final Logger LOG = LogManager.getLogger(LogdClient.class);
  private static final int TIME_OUT_SECONDS = 5;
  private static final String LOCAL_HOST = "localhost";

  private int port;
  private final ManagedChannel channel;
  private final LogdServiceGrpc.LogdServiceBlockingStub blockingStub;
  private final LogdServiceGrpc.LogdServiceStub asyncStub;

  private final Map<Integer, LogdStream> fileIdToLogdStream = new ConcurrentHashMap<>();
  private final Map<Integer, StreamObserver<Status>> responseObservers = new ConcurrentHashMap<>();
  private final Map<Integer, String> fileIdToPath = new ConcurrentHashMap<>();
  private final StreamObserverFactory streamObserverFactory;

  /**
   * Constructs a LogdClient that is connected to localhost at provided port number.
   *
   * @param port port number
   */
  public LogdClient(int port) {
    this(LOCAL_HOST, port);
  }

  /**
   * Constructs a LogdClient with the provided hostname and port number.
   *
   * @param host host name
   * @param port port number
   */
  public LogdClient(String host, int port) {
    this(host, port, new DefaultStreamObserverFactory());
  }

  /**
   * Constructs a LogdClient with the provided hostname, port number and an implementation of
   * StreamObserverFactory.
   *
   * @param host host name
   * @param port port number
   * @param streamObserverFactory an implementation of StreamObserverFactory
   */
  public LogdClient(String host, int port, StreamObserverFactory streamObserverFactory) {
    this(ManagedChannelBuilder.forAddress(host, port).usePlaintext(), streamObserverFactory);
    this.port = port;
    LOG.info("Channel established to {} at port {}", host, port);
  }

  /**
   * Constructs a LogdClient with the provided channel
   *
   * @param channelBuilder a channel to LogD server
   */
  @VisibleForTesting
  public LogdClient(
      ManagedChannelBuilder<?> channelBuilder, StreamObserverFactory streamObserverFactory) {
    channel = channelBuilder.build();
    blockingStub = LogdServiceGrpc.newBlockingStub(channel);
    asyncStub = LogdServiceGrpc.newStub(channel);
    this.streamObserverFactory = streamObserverFactory;
  }

  @Override
  public ManagedChannel getChannel() {
    return channel;
  }

  @Override
  public int getPort() {
    return port;
  }

  @Override
  public void shutdown() {
    closeAllStreams();
    try {
      LOG.info(
          "Awaiting termination of channel to logD server. Waiting for up to {} seconds...",
          TIME_OUT_SECONDS);
      channel.shutdown().awaitTermination(TIME_OUT_SECONDS, TimeUnit.SECONDS);
      if (!channel.isTerminated()) {
        LOG.warn(
            "Channel is still open after shutdown request and {} seconds timeout. Shutting down forcefully...",
            TIME_OUT_SECONDS);
        channel.shutdownNow();
        LOG.info("Successfully shut down LogD client.");
      }
    } catch (InterruptedException e) {
      channel.shutdownNow();
      LOG.info("Shutdown interrupted. Shutting down LogD client forcefully...");
    }
  }

  @Override
  public int createLogFile(String path, LogType logType) throws LogDaemonException {
    CreateLogResponse response;

    try {
      response =
          blockingStub.createLogFile(
              CreateLogRequest.newBuilder().setLogFilePath(path).setLogType(logType).build());
      int logdFileId = response.getLogId();
      fileIdToPath.put(logdFileId, path);

      return logdFileId;
    } catch (StatusRuntimeException e) {
      LOG.error("LogD failed to return response with a file identifier: " + e.getStatus(), e);
      throw new LogDaemonException(
          e, "LogD failed to create a log file at %s, of type %s", path, logType);
    }
  }

  @Override
  public LogdStream openLog(int logFileId) {
    // Client calls this method with the returned generated id from calling createLogFile.
    // LogD server will then return the client with a requestObserver which observes and processes
    //   incoming logs from client i.e. subsequent logs are sent via requestObserver.onNext(...)
    // The returned requestObserver will be wrapped in a LogdStream and returned to the caller
    responseObservers.computeIfAbsent(
        logFileId,
        newLogFileId -> streamObserverFactory.createStreamObserver(fileIdToPath.get(newLogFileId)));

    LOG.info("Opening a logd stream to {}", fileIdToPath.get(logFileId));
    return fileIdToLogdStream.computeIfAbsent(
        logFileId,
        newLogFileId ->
            new LogdStream(asyncStub.openLog(responseObservers.get(newLogFileId)), newLogFileId));
  }

  @Override
  public void requestLogdServerShutdown() throws LogDaemonException {
    try {
      // Client sends an empty message request to signal LogD to shutdown its server
      // We do not care about the status response for now
      blockingStub.shutdownServer(ShutdownRequest.newBuilder().build());
    } catch (StatusRuntimeException e) {
      LOG.error("LogD failed to return a response: {}", e.getStatus(), e);
      throw new LogDaemonException(e, "LogD failed to return a response: %s", e.getStatus());
    }
  }

  private void closeAllStreams() {
    LOG.info("Force closing all logd streams before client shutdown...");
    for (LogdStream logdStream : fileIdToLogdStream.values()) {
      logdStream.close();
    }
  }
}
