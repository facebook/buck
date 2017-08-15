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

import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildModeInfo;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.google.common.base.Preconditions;
import java.io.IOException;

public class CoordinatorAndMinionInfoSetter implements CoordinatorModeRunner.EventListener {
  private final DistBuildService service;
  private final StampedeId stampedeId;
  private final String minionQueue;

  public CoordinatorAndMinionInfoSetter(
      DistBuildService service, StampedeId stampedeId, String minionQueue) {
    this.service = service;
    this.stampedeId = stampedeId;
    this.minionQueue = minionQueue;
  }

  @Override
  public void onThriftServerStarted(String address, int port) throws IOException {
    service.setCoordinator(stampedeId, port, address);
    BuildJob buildJob = service.getCurrentBuildJobState(stampedeId);
    Preconditions.checkArgument(buildJob.isSetBuildModeInfo());
    BuildModeInfo buildModeInfo = buildJob.getBuildModeInfo();
    if (buildModeInfo.isSetNumberOfMinions() && buildModeInfo.getNumberOfMinions() > 0) {
      service.enqueueMinions(stampedeId, buildModeInfo.getNumberOfMinions(), minionQueue);
    }
  }
}
