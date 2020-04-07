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
import com.facebook.buck.logd.proto.CreateLogDirRequest;
import com.facebook.buck.logd.proto.CreateLogRequest;
import com.facebook.buck.logd.proto.CreateLogResponse;
import com.facebook.buck.logd.proto.LogMessage;
import com.facebook.buck.logd.proto.LogType;
import com.facebook.buck.logd.proto.LogdServiceGrpc;
import com.facebook.buck.logd.proto.ShutdownRequest;
import com.facebook.buck.util.ExitCode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.SettableFuture;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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
  private final LogdServiceImpl service;
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
    this.service = new LogdServiceImpl();
    this.server = serverBuilder.addService(service).build();
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

    new Thread(
            () -> {
              try {
                if (service.isLogdServiceFinished().get()) {
                  LOG.info("LogD service triggered shutdown. Server shutting down...");
                }
              } catch (InterruptedException e) {
                LOG.info("Interrupted...", e);
                Thread.currentThread().interrupt();
              } catch (ExecutionException e) {
                LOG.error("Execution exception...", e);
              } finally {
                stop();
              }
            })
        .start();
  }

  /** Shuts down logD server. */
  @Override
  public void stop() {
    if (server != null && !server.isTerminated()) {
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

    service.closeAllStreams();
  }

  private static class LogdServiceImpl extends LogdServiceGrpc.LogdServiceImplBase {
    private static final boolean IS_UPLOADING_LOG_TO_STORAGE =
        Boolean.parseBoolean(System.getProperty("logd.manifold.upload", "false"));

    static {
      LOG.info("logd.manifold.upload: {}", IS_UPLOADING_LOG_TO_STORAGE);
    }

    private final SettableFuture<Boolean> logdServiceFinished = SettableFuture.create();
    private final Map<Integer, BufferedWriter> logStreams = new ConcurrentHashMap<>();
    private final LogFileIdGenerator logFileIdGenerator = new LogFileIdGenerator();
    private final Map<Integer, LogFileData> fileIdToFileData = new ConcurrentHashMap<>();
    private final Map<LogType, LogFileData> logTypeToFileData = new ConcurrentHashMap<>();

    @Override
    public void createLogDir(
        CreateLogDirRequest createLogDirRequest, StreamObserver<Status> responseObserver) {
      String buildId = createLogDirRequest.getBuildId();

      if (IS_UPLOADING_LOG_TO_STORAGE) {
        LogdUploader.sendCreateLogDirRequestToController(buildId);
      }

      // TODO(qahoang): Implement LogD so that it is responsible for creating log dir in
      // file-system
      responseObserver.onNext(Status.newBuilder().setCode(ExitCode.SUCCESS.getCode()).build());
      responseObserver.onCompleted();
    }

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
        LOG.error("Failed to create log file at {}", logFilePath, e);
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

          if (!fileIdToFileData.containsKey(logId)) {
            throw new LogDaemonException("The provided logFileId " + logId + " does not exist.");
          }

          String path = fileIdToFileData.get(logId).getLogFilePath();
          try {
            appendLog(logId, logMessage.getLogMessage());
          } catch (IOException e) {
            LOG.error("Failed to append log file at {}", path, e);
            throw new LogDaemonException(e, "Failed to append log file at %s", path);
          }
        }

        @Override
        public void onError(Throwable t) {
          LOG.error("log appending cancelled", t);
        }

        @Override
        public void onCompleted() {
          // closes grpc stream and do nothing else because there might be multiple grpc streams
          // writing to the same LogdMultiWriter
          responseObserver.onNext(Status.newBuilder().setCode(ExitCode.SUCCESS.getCode()).build());
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public void shutdownServer(ShutdownRequest request, StreamObserver<Status> responseObserver) {
      responseObserver.onNext(Status.newBuilder().build());
      responseObserver.onCompleted();
      LOG.info("Signaling LogD server to shutdown...");
      logdServiceFinished.set(true);
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
      String filePath = createLogRequest.getLogFilePath();
      Path logFilePath = Paths.get(filePath);
      String buildId = createLogRequest.getBuildId();
      LogType logType = createLogRequest.getLogType();

      // TODO(qahoang): change this map to logFileNameToFileData when we move from log type and path
      // to using log file name
      if (logTypeToFileData.containsKey(logType)) {
        return CreateLogResponse.newBuilder()
            .setLogId(logTypeToFileData.get(logType).getLogFileId())
            .build();
      }

      try {
        Files.createDirectories(logFilePath.getParent());

        int genFileId = logFileIdGenerator.generateFileId();
        logStreams.put(
            genFileId,
            Files.newBufferedWriter(
                logFilePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        LOG.info("LogD opened new writer stream to {}", logFilePath.toString());

        int offset = 0;
        if (IS_UPLOADING_LOG_TO_STORAGE) {
          Optional<Integer> result =
              LogdUploader.sendCreateLogFileRequestToController(buildId, logType);
          if (result.isPresent()) {
            offset = result.get();
            LOG.info(
                "LogD created a new log file of type '{}' in storage",
                logType.getValueDescriptor().getName());
          } else {
            LOG.error(
                "Failed to create new log file of type '{}' in storage",
                logType.getValueDescriptor().getName());
          }
        }

        LogFileData newFile = new LogFileData(genFileId, buildId, logType, filePath, offset);
        fileIdToFileData.put(genFileId, newFile);
        logTypeToFileData.put(logType, newFile);

        return CreateLogResponse.newBuilder().setLogId(genFileId).build();
      } catch (IOException e) {
        LOG.error("LogD failed to open a writer stream to {}", filePath, e);
        throw new LogDaemonException(e, "LogD failed to open a writer stream to %s", filePath);
      }
    }

    public SettableFuture<Boolean> isLogdServiceFinished() {
      return logdServiceFinished;
    }

    private void appendLog(int fileId, String message) throws IOException {
      BufferedWriter writer = logStreams.get(fileId);
      writer.write(message);

      if (IS_UPLOADING_LOG_TO_STORAGE) {
        LogFileData logFileData = fileIdToFileData.get(fileId);
        logFileData.updateStorage(message);
      }
    }

    private void closeAllStreams() {
      for (Integer logFileId : logStreams.keySet()) {
        String logFilePath = fileIdToFileData.remove(logFileId).getLogFilePath();
        BufferedWriter writer = logStreams.remove(logFileId);
        try {
          writer.close();
          LOG.info("LogD closed stream to log file at {}", logFilePath);
        } catch (IOException e) {
          LOG.error("LogD failed to close stream to log file at {}", logFilePath, e);
        }
      }
    }
  }
}
