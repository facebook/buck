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

package com.facebook.buck.logd.server;

import com.facebook.buck.logd.LogDaemonException;
import com.facebook.buck.logd.proto.CreateLogRequest;
import com.facebook.buck.logd.proto.CreateLogResponse;
import com.facebook.buck.logd.proto.LogMessage;
import com.facebook.buck.logd.proto.LogdServiceGrpc;
import com.facebook.buck.util.ExitCode;
import com.google.common.annotations.VisibleForTesting;
import com.google.rpc.Status;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Given a port number, logD server is responsible for handling log writing to a filesystem and to
 * storage.
 */
public class LogdServer implements LogDaemonServer {
  private static final Logger LOG = LogManager.getLogger();
  private static final int TIME_OUT_SECONDS = 5;

  private final Server server;
  private final int port;

  /**
   * Construct a logD server at requested port number.
   *
   * @param port port number
   */
  public LogdServer(int port) {
    this(port, ServerBuilder.forPort(port));
  }

  /**
   * Default to log streaming service.
   *
   * @param port port number
   * @param serverBuilder server created at requested port number
   */
  @VisibleForTesting
  public LogdServer(int port, ServerBuilder<?> serverBuilder) {
    this.port = port;
    this.server = serverBuilder.addService(new LogdServiceImpl()).build();
  }

  /**
   * Starts logD server listening on provided port in constructor.
   *
   * @throws IOException if unable to bind
   */
  @Override
  public void start() throws IOException {
    server.start();
    LOG.info("Server started, listening on port {}", port);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  // Use stderr here since the LOG may have been reset by its JVM shutdown hook.
                  System.err.println("*** shutting down logD server since JVM is shutting down");
                  stop();
                  System.err.println("*** server shut down");
                }));
  }

  /** Shuts down logD server. */
  @Override
  public void stop() {
    if (server != null) {
      try {
        LOG.info(
            "Awaiting termination of logD server. Waiting for up to {} seconds...",
            TIME_OUT_SECONDS);
        server.shutdown().awaitTermination(TIME_OUT_SECONDS, TimeUnit.SECONDS);
        if (!server.isTerminated()) {
          LOG.warn(
              "LogD server is still running after shutdown request and {} seconds timeout. Shutting down forcefully...",
              TIME_OUT_SECONDS);
          server.shutdownNow();
          LOG.info("Successfully terminated LogD server.");
        }
      } catch (InterruptedException e) {
        server.shutdownNow();
        LOG.info("Shutdown interrupted. Shutting down LogD server forcefully...");
      }
    }
  }

  private static class LogdServiceImpl extends LogdServiceGrpc.LogdServiceImplBase {
    private Map<Integer, BufferedWriter> logStreams = new ConcurrentHashMap<>();
    private Map<Integer, String> fileIdToPath = new ConcurrentHashMap<>();
    private final LogFileIdGenerator logFileIdGenerator = new LogFileIdGenerator();

    /**
     * LogD opens a file upon request from client and returns a generated int identifier.
     *
     * @param request request from client to create a log file for log streaming
     * @param responseObserver a StreamObserver listening for logD server to return a response
     */
    @Override
    public void createLogFile(
        CreateLogRequest request, StreamObserver<CreateLogResponse> responseObserver) {
      String logFilePath = request.getLogFilePath();
      try {
        responseObserver.onNext(createFile(request));
        LOG.debug("Log file created at {}", logFilePath);
      } catch (LogDaemonException e) {
        LOG.error("Failed to create log file at " + logFilePath, e);
        responseObserver.onError(e);
      }

      responseObserver.onCompleted();
    }

    /**
     * LogD listens to log messages streamed from client. Once client finishes streaming, server
     * closes corresponding FileOutputStream.
     *
     * @param responseObserver a StreamObserver object which sends a Status message after client has
     *     closed the LogMessage stream.
     * @return a StreamObserver that observes and processes incoming logs from client
     */
    @Override
    public StreamObserver<LogMessage> openLog(StreamObserver<Status> responseObserver) {
      return new StreamObserver<LogMessage>() {
        private int logId;

        @Override
        public void onNext(LogMessage logMessage) throws LogDaemonException {
          logId = logMessage.getLogId();

          if (!fileIdToPath.containsKey(logId)) {
            throw new LogDaemonException("The provided logFileId " + logId + " does not exist.");
          }

          String path = fileIdToPath.get(logId);
          try {
            appendLog(logId, logMessage.getLogMessage());
          } catch (IOException e) {
            LOG.error("Failed to append log file at " + path, e);
            throw new LogDaemonException(e, "Failed to append log file at %s", path);
          }
        }

        @Override
        public void onError(Throwable t) {
          LOG.error("log appending cancelled", t);
        }

        @Override
        public void onCompleted() {
          // if client calls onCompleted and closes the stream
          // then close FileOutputStream of corresponding StreamObserver
          String logFilePath = fileIdToPath.get(logId);
          try {
            logStreams.get(logId).close();
            responseObserver.onNext(
                Status.newBuilder()
                    .setCode(ExitCode.SUCCESS.getCode())
                    .setMessage("LogD closed stream to " + logFilePath)
                    .build());

          } catch (IOException e) {
            responseObserver.onNext(
                Status.newBuilder()
                    .setCode(ExitCode.BUILD_ERROR.getCode())
                    .setMessage("Failed to close log stream to " + logFilePath)
                    .build());
          }

          responseObserver.onCompleted();
        }
      };
    }

    /**
     * @param createLogRequest a request sent from client to logD to create a log file at the
     *     requested path, type of file is identified by the requested log type.
     * @return a generated int identifier corresponding with the created file
     * @throws LogDaemonException if file already exists or logD fails to create a log file at
     *     requested path for some reasons
     */
    private CreateLogResponse createFile(CreateLogRequest createLogRequest)
        throws LogDaemonException {
      // TODO(qahoang): decide what to do with logType
      String filePath = createLogRequest.getLogFilePath();

      Path logFilePath = Paths.get(filePath);

      try {
        Files.createDirectories(logFilePath.getParent());
        Files.createFile(logFilePath);
        LOG.info("Created new file at {}", logFilePath.toString());

        int genFileId = logFileIdGenerator.generateFileId();
        fileIdToPath.put(genFileId, filePath);
        logStreams.put(genFileId, Files.newBufferedWriter(logFilePath, StandardOpenOption.APPEND));

        return CreateLogResponse.newBuilder().setLogId(genFileId).build();
      } catch (IOException e) {
        LOG.error("LogD failed to create a file at " + filePath, e);
        throw new LogDaemonException(e, "LogD failed to create a file at %s", filePath);
      }
    }

    private void appendLog(int fileId, String message) throws IOException {
      BufferedWriter writer = logStreams.get(fileId);

      writer.write(message);
      writer.newLine();
    }
  }
}
