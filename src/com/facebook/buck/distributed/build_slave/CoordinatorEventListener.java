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

import com.facebook.buck.distributed.BuildStatusUtil;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.build_slave.ThriftCoordinatorServer.ExitState;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildModeInfo;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.google.common.base.Preconditions;
import java.io.IOException;

/** Listener to events from the Coordinator. */
public class CoordinatorEventListener implements ThriftCoordinatorServer.EventListener {
  private final DistBuildService service;
  private final StampedeId stampedeId;
  private final String minionQueue;
  private boolean islocalMinionAlsoRunning;

  public CoordinatorEventListener(
      DistBuildService service,
      StampedeId stampedeId,
      String minionQueue,
      boolean islocalMinionAlsoRunning) {
    this.service = service;
    this.stampedeId = stampedeId;
    this.minionQueue = minionQueue;
    this.islocalMinionAlsoRunning = islocalMinionAlsoRunning;
  }

  @Override
  public void onThriftServerStarted(String address, int port) throws IOException {
    service.setCoordinator(stampedeId, port, address);
    BuildJob buildJob = service.getCurrentBuildJobState(stampedeId);
    Preconditions.checkArgument(buildJob.isSetBuildModeInfo());
    BuildModeInfo buildModeInfo = buildJob.getBuildModeInfo();
    if (!buildModeInfo.isSetNumberOfMinions()) {
      return;
    }

    int requiredNumberOfMinions =
        islocalMinionAlsoRunning
            ? buildModeInfo.getNumberOfMinions() - 1
            : buildModeInfo.getNumberOfMinions();
    if (requiredNumberOfMinions > 0) {
      service.enqueueMinions(stampedeId, requiredNumberOfMinions, minionQueue);
    }
  }

  @Override
  public void onThriftServerClosing(ExitState exitState) throws IOException {
    if (exitState.wasExitCodeSetByServers()) {
      // No point in trying to set the final build status again.
      return;
    }

    int buildExitCode = exitState.getExitCode();
    BuildStatus status = BuildStatusUtil.exitCodeToBuildStatus(buildExitCode);
    String message =
        String.format(
            "Coordinator [%s] exited with code=[%d] message=[%s] and status=[%s].",
            HostnameFetching.getHostname(),
            buildExitCode,
            exitState.getExitMessage(),
            BuildStatusUtil.exitCodeToBuildStatus(buildExitCode).toString());
    service.setFinalBuildStatus(stampedeId, status, message);
  }
}
