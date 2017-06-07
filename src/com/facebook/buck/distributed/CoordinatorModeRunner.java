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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.StampedeId;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;

public class CoordinatorModeRunner implements DistBuildModeRunner {

  private final BuildTargetsQueue queue;
  private final int coordinatorPort;
  private final StampedeId stampedeId;

  public CoordinatorModeRunner(
      int coordinatorPort, BuildTargetsQueue queue, StampedeId stampedeId) {
    this.stampedeId = stampedeId;
    Preconditions.checkArgument(
        coordinatorPort > 0, "The coordinator's port needs to be a positive integer.");
    this.queue = queue;
    this.coordinatorPort = coordinatorPort;
  }

  @Override
  public int runAndReturnExitCode() throws IOException {
    try (AsyncCoordinatorRun run = new AsyncCoordinatorRun(queue)) {
      return run.getExitCode();
    }
  }

  public AsyncCoordinatorRun runAsyncAndReturnExitCode() throws IOException {
    return new AsyncCoordinatorRun(queue);
  }

  public class AsyncCoordinatorRun implements Closeable {
    private final ThriftCoordinatorServer server;

    private AsyncCoordinatorRun(BuildTargetsQueue queue) throws IOException {
      this.server = new ThriftCoordinatorServer(coordinatorPort, queue, stampedeId);
      this.server.start();
    }

    public int getExitCode() {
      return server.waitUntilBuildCompletesAndReturnExitCode();
    }

    @Override
    public void close() throws IOException {
      this.server.close();
    }
  }
}
