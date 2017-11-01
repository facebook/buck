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

import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.OptionalInt;

public class CoordinatorModeRunner implements DistBuildModeRunner {
  private static final Logger LOG = Logger.get(CoordinatorModeRunner.class);

  // Note that this is only the port specified by the caller.
  // If this is zero, the server might be running on any other free port.
  // TODO(shivanker): Add a getPort() method in case we need to use the actual port here.
  private final OptionalInt coordinatorPort;

  private final ListenableFuture<BuildTargetsQueue> queue;
  private final StampedeId stampedeId;
  private final Path logDirectoryPath;
  private final ThriftCoordinatorServer.EventListener eventListener;

  /**
   * Constructor
   *
   * @param coordinatorPort - Passing in an empty optional will pick up a random free port.
   */
  public CoordinatorModeRunner(
      OptionalInt coordinatorPort,
      ListenableFuture<BuildTargetsQueue> queue,
      StampedeId stampedeId,
      ThriftCoordinatorServer.EventListener eventListener,
      Path logDirectoryPath) {
    this.stampedeId = stampedeId;
    coordinatorPort.ifPresent(CoordinatorModeRunner::validatePort);
    this.logDirectoryPath = logDirectoryPath;
    this.queue = queue;
    this.coordinatorPort = coordinatorPort;
    this.eventListener = eventListener;
  }

  public CoordinatorModeRunner(
      ListenableFuture<BuildTargetsQueue> queue,
      StampedeId stampedeId,
      ThriftCoordinatorServer.EventListener eventListener,
      Path logDirectoryPath) {
    this(OptionalInt.empty(), queue, stampedeId, eventListener, logDirectoryPath);
  }

  @Override
  public int runAndReturnExitCode() throws IOException {
    try (AsyncCoordinatorRun run = new AsyncCoordinatorRun(queue)) {
      return run.getExitCode();
    }
  }

  /**
   * Function to verify that the specified port lies in the non-kernel-reserved port range.
   *
   * @throws IllegalStateException
   */
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

  public AsyncCoordinatorRun runAsyncAndReturnExitCode() throws IOException {
    return new AsyncCoordinatorRun(queue);
  }

  public class AsyncCoordinatorRun implements Closeable {

    private final ThriftCoordinatorServer server;

    private AsyncCoordinatorRun(ListenableFuture<BuildTargetsQueue> queue) throws IOException {
      this.server = new ThriftCoordinatorServer(coordinatorPort, queue, stampedeId, eventListener);
      this.server.start();
    }

    public int getExitCode() {
      return server.waitUntilBuildCompletesAndReturnExitCode();
    }

    public int getPort() {
      return this.server.getPort();
    }

    @Override
    public void close() throws IOException {
      this.server.close();

      try {
        this.server
            .traceSnapshot()
            .dumpToChromeTrace(logDirectoryPath.resolve(BuckConstant.DIST_BUILD_TRACE_FILE_NAME));
      } catch (Exception e) {
        LOG.warn("Failed to write chrome trace", e);
      }
    }
  }
}
