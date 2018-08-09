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
import com.facebook.buck.distributed.DistBuildUtil;
import com.facebook.buck.distributed.build_slave.ThriftCoordinatorServer.ExitState;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildModeInfo;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.MinionRequirement;
import com.facebook.buck.distributed.thrift.MinionType;
import com.facebook.buck.distributed.thrift.SchedulingEnvironmentType;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.OptionalInt;

/** Listener to events from the Coordinator. */
public class CoordinatorEventListener
    implements ThriftCoordinatorServer.EventListener, MinionCountProvider {
  private static final Logger LOG = Logger.get(CoordinatorEventListener.class);
  private final DistBuildService service;
  private final StampedeId stampedeId;
  private final String buildLabel;
  private final MinionQueueProvider minionQueueProvider;
  private final String minionRegion;
  private boolean islocalMinionAlsoRunning;
  private volatile OptionalInt totalMinionCount = OptionalInt.empty();

  public CoordinatorEventListener(
      DistBuildService service,
      StampedeId stampedeId,
      String buildLabel,
      MinionQueueProvider minionQueueProvider,
      boolean islocalMinionAlsoRunning,
      String minionRegion) {
    this.service = service;
    this.stampedeId = stampedeId;
    this.buildLabel = buildLabel;
    this.minionQueueProvider = minionQueueProvider;
    this.islocalMinionAlsoRunning = islocalMinionAlsoRunning;
    this.minionRegion = minionRegion;
  }

  @Override
  public void onThriftServerStarted(String address, int port) throws IOException {
    service.setCoordinator(stampedeId, port, address);
    BuildJob buildJob = service.getCurrentBuildJobState(stampedeId);
    Preconditions.checkArgument(buildJob.isSetBuildModeInfo());
    BuildModeInfo buildModeInfo = buildJob.getBuildModeInfo();

    if (!buildModeInfo.isSetMinionRequirements() && !buildModeInfo.isSetTotalNumberOfMinions()) {
      return;
    }

    // TODO(alisdair): remove in future once minion requirements fully supported.
    if (buildModeInfo.isSetTotalNumberOfMinions() && !buildModeInfo.isSetMinionRequirements()) {
      buildModeInfo.setMinionRequirements(
          (DistBuildUtil.createMinionRequirements(
              buildModeInfo.getMode(),
              SchedulingEnvironmentType.IDENTICAL_HARDWARE,
              buildModeInfo.getTotalNumberOfMinions(),
              0)));
    }

    totalMinionCount =
        OptionalInt.of(DistBuildUtil.countMinions(buildModeInfo.getMinionRequirements()));

    Preconditions.checkArgument(buildModeInfo.getMinionRequirements().isSetRequirements());
    for (MinionRequirement requirement : buildModeInfo.getMinionRequirements().getRequirements()) {
      MinionType minionType = requirement.getMinionType();
      int requiredCount = requirement.getRequiredCount();
      if (minionType == MinionType.STANDARD_SPEC && islocalMinionAlsoRunning) {
        requiredCount -= 1; // One minion is already running, so no need to schedule again remotely.
      }

      String minionQueue = minionQueueProvider.getMinionQueue(minionType);
      LOG.info("Requesting [%d] minions of type [%s]", requiredCount, minionType.name());

      service.enqueueMinions(
          stampedeId, buildLabel, requiredCount, minionQueue, minionType, minionRegion);
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

  @Override
  public OptionalInt getTotalMinionCount() {
    return totalMinionCount;
  }
}
