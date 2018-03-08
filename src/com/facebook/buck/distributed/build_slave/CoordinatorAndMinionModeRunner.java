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

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.ExitCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;

/**
 * {@link DistBuildModeRunner} implementation for running a distributed build as coordinator as well
 * as minion on a remote machine.
 */
public class CoordinatorAndMinionModeRunner extends AbstractDistBuildModeRunner {
  private static final Logger LOG = Logger.get(CoordinatorAndMinionModeRunner.class);

  private final CoordinatorModeRunner coordinatorModeRunner;
  private final MinionModeRunner minionModeRunner;

  public CoordinatorAndMinionModeRunner(
      CoordinatorModeRunner coordinatorModeRunner, MinionModeRunner minionModeRunner) {
    this.coordinatorModeRunner = coordinatorModeRunner;
    this.minionModeRunner = minionModeRunner;
  }

  @Override
  public ListenableFuture<?> getAsyncPrepFuture() {
    return Futures.allAsList(
        coordinatorModeRunner.getAsyncPrepFuture(), minionModeRunner.getAsyncPrepFuture());
  }

  @Override
  public ExitCode runAndReturnExitCode(HeartbeatService heartbeatService) throws IOException {
    LOG.debug("Running the Coordinator in async mode...");
    try (CoordinatorModeRunner.AsyncCoordinatorRun coordinatorRun =
        coordinatorModeRunner.runAsyncAndReturnExitCode(heartbeatService)) {
      LOG.debug("Running the Minion with the Coordinator in the background...");
      minionModeRunner.setCoordinatorPort(coordinatorRun.getPort());
      try {
        // We only care about the Coordinator exit code that is controlling this process.
        minionModeRunner.runAndReturnExitCode(heartbeatService);
      } catch (IOException | InterruptedException e) {
        LOG.error(e, "Minion crashed with an exception.");
      }

      LOG.debug("Getting the coordinator exit code...");
      return coordinatorRun.getExitCode();
    } finally {
      LOG.debug("Waiting for the coordinator async run to close()...");
    }
  }
}
