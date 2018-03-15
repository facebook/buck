/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.build_slave.HeartbeatService.HeartbeatCallback;
import com.facebook.buck.distributed.build_slave.ThriftCoordinatorServer.EventListener;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.trace.uploader.launcher.UploaderLauncher;
import com.facebook.buck.util.trace.uploader.types.CompressionType;
import com.google.common.base.Preconditions;
import com.google.common.io.Closer;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@link DistBuildModeRunner} implementation for running a distributed build as coordinator only.
 */
public class CoordinatorModeRunner extends AbstractDistBuildModeRunner {

  private static final Logger LOG = Logger.get(CoordinatorModeRunner.class);

  // Note that this is only the port specified by the caller.
  // If this is zero, the server might be running on any other free port.
  // TODO(shivanker): Add a getPort() method in case we need to use the actual port here.
  private final OptionalInt coordinatorPort;

  private final ListenableFuture<BuildTargetsQueue> queue;
  private final StampedeId stampedeId;
  private final Optional<BuildId> clientBuildId;
  private final Path logDirectoryPath;
  private final ThriftCoordinatorServer.EventListener eventListener;
  private final CoordinatorBuildRuleEventsPublisher coordinatorBuildRuleEventsPublisher;
  private final DistBuildService distBuildService;
  private final MinionHealthTracker minionHealthTracker;
  private final Optional<URI> traceUploadUri;
  private final MinionCountProvider minionCountProvider;

  /** Constructor. */
  public CoordinatorModeRunner(
      OptionalInt coordinatorPort,
      ListenableFuture<BuildTargetsQueue> queue,
      StampedeId stampedeId,
      EventListener eventListener,
      Path logDirectoryPath,
      Optional<BuildId> clientBuildId,
      Optional<URI> traceUploadUri,
      CoordinatorBuildRuleEventsPublisher coordinatorBuildRuleEventsPublisher,
      DistBuildService distBuildService,
      MinionHealthTracker minionHealthTracker,
      MinionCountProvider minionCountProvider) {
    this.stampedeId = stampedeId;
    this.clientBuildId = clientBuildId;
    this.traceUploadUri = traceUploadUri;
    this.minionHealthTracker = minionHealthTracker;
    coordinatorPort.ifPresent(CoordinatorModeRunner::validatePort);
    this.logDirectoryPath = logDirectoryPath;
    this.queue = queue;
    this.coordinatorPort = coordinatorPort;
    this.eventListener = eventListener;
    this.coordinatorBuildRuleEventsPublisher = coordinatorBuildRuleEventsPublisher;
    this.distBuildService = distBuildService;
    this.minionCountProvider = minionCountProvider;
  }

  public CoordinatorModeRunner(
      ListenableFuture<BuildTargetsQueue> queue,
      StampedeId stampedeId,
      EventListener eventListener,
      Path logDirectoryPath,
      CoordinatorBuildRuleEventsPublisher coordinatorBuildRuleEventsPublisher,
      DistBuildService distBuildService,
      Optional<BuildId> clientBuildId,
      Optional<URI> traceUploadUri,
      MinionHealthTracker minionHealthTracker,
      MinionCountProvider minionCountProvider) {
    this(
        OptionalInt.empty(),
        queue,
        stampedeId,
        eventListener,
        logDirectoryPath,
        clientBuildId,
        traceUploadUri,
        coordinatorBuildRuleEventsPublisher,
        distBuildService,
        minionHealthTracker,
        minionCountProvider);
  }

  @Override
  public ListenableFuture<?> getAsyncPrepFuture() {
    return queue;
  }

  @Override
  public ExitCode runAndReturnExitCode(HeartbeatService heartbeatService) throws IOException {
    try (AsyncCoordinatorRun run = new AsyncCoordinatorRun(heartbeatService, queue)) {
      return run.getExitCode();
    }
  }

  /** Reports back to the servers that the coordinator is healthy and alive. */
  public static HeartbeatCallback createHeartbeatCallback(
      StampedeId stampedeId, DistBuildService service) {
    return new HeartbeatCallback() {
      @Override
      public void runHeartbeat() throws IOException {
        service.reportCoordinatorIsAlive(stampedeId);
      }
    };
  }

  private HeartbeatCallback createHeartbeatCallback() {
    return createHeartbeatCallback(stampedeId, distBuildService);
  }

  /** Function to verify that the specified port lies in the non-kernel-reserved port range. */
  public static void validatePort(int port) {
    Preconditions.checkState(
        port != 0,
        "Invalid coordinator port: "
            + "Specified coordinator port cannot be zero. See constructor.");
    Preconditions.checkState(
        port > 1024, "Invalid coordinator port: " + "Cannot bind to reserved port [%d].", port);
    Preconditions.checkState(
        port < 65536,
        "Invalid coordinator port: " + "Network port [%d] cannot be more than 2 bytes.",
        port);
  }

  public AsyncCoordinatorRun runAsyncAndReturnExitCode(HeartbeatService service)
      throws IOException {
    return new AsyncCoordinatorRun(service, queue);
  }

  public class AsyncCoordinatorRun implements Closeable {

    private final Closer closer;
    private final ThriftCoordinatorServer server;

    private AsyncCoordinatorRun(HeartbeatService service, ListenableFuture<BuildTargetsQueue> queue)
        throws IOException {
      this.closer = Closer.create();
      this.server =
          closer.register(
              new ThriftCoordinatorServer(
                  coordinatorPort,
                  queue,
                  stampedeId,
                  eventListener,
                  coordinatorBuildRuleEventsPublisher,
                  minionHealthTracker,
                  distBuildService,
                  minionCountProvider));
      this.server.start();
      this.closer.register(
          service.addCallback("ReportCoordinatorAlive", createHeartbeatCallback()));
      this.closer.register(
          service.addCallback("MinionLivenessCheck", () -> server.checkAllMinionsAreAlive()));
      this.closer.register(
          service.addCallback("BuildStatusCheck", () -> server.checkBuildStatusIsNotTerminated()));
    }

    public ExitCode getExitCode() {
      return ExitCode.map(server.waitUntilBuildCompletesAndReturnExitCode());
    }

    public int getPort() {
      return this.server.getPort();
    }

    @Override
    public void close() throws IOException {
      closer.close();

      // TODO(shivanker): This should be async, but blocking the process from shutting down.
      dumpAndUploadChromeTrace();
    }

    private void dumpAndUploadChromeTrace() {
      try {
        Path traceFilePath = logDirectoryPath.resolve(BuckConstant.DIST_BUILD_TRACE_FILE_NAME);
        this.server.generateTrace().dumpToChromeTrace(traceFilePath);

        if (!clientBuildId.isPresent()) {
          LOG.warn("Not uploading distbuild chrome trace because original build uuid is unset");
          return;
        }

        if (!traceUploadUri.isPresent()) {
          LOG.info("Not uploading distbuild chrome trace because traceUploadUri is unset");
          return;
        }

        BuildId buildId = clientBuildId.get();
        URI uploadUri = traceUploadUri.get();

        Path uploadLogFile = logDirectoryPath.resolve("upload-dist-build-build-trace.log");
        UploaderLauncher.uploadInBackground(
            buildId, traceFilePath, "dist_build", uploadUri, uploadLogFile, CompressionType.GZIP);
      } catch (IOException e) {
        LOG.warn(e, "Failed to write or upload distbuild chrome trace.");
      }
    }
  }
}
