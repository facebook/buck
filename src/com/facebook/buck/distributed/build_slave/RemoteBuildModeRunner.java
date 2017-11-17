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

import com.facebook.buck.command.BuildExecutor;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.thrift.StampedeId;
import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;

/** Executes stampede in remote build mode. */
public class RemoteBuildModeRunner implements DistBuildModeRunner {

  /** Sets the final BuildStatus of the BuildJob. */
  public interface FinalBuildStatusSetter {

    void setFinalBuildStatus(int exitCode) throws IOException;
  }

  private final BuildExecutor localBuildExecutor;
  private final Iterable<String> topLevelTargetsToBuild;
  private final FinalBuildStatusSetter setter;
  private final DistBuildService distBuildService;
  private final StampedeId stampedeId;

  public RemoteBuildModeRunner(
      BuildExecutor localBuildExecutor,
      Iterable<String> topLevelTargetsToBuild,
      FinalBuildStatusSetter setter,
      DistBuildService distBuildService,
      StampedeId stampedeId) {
    this.localBuildExecutor = localBuildExecutor;
    this.topLevelTargetsToBuild = topLevelTargetsToBuild;
    this.setter = setter;
    this.distBuildService = distBuildService;
    this.stampedeId = stampedeId;
  }

  @Override
  public int runAndReturnExitCode(HeartbeatService heartbeatService)
      throws IOException, InterruptedException {
    try (Closeable healthCheck =
        heartbeatService.addCallback(
            "RemoteBuilderIsAlive",
            CoordinatorModeRunner.createHeartbeatCallback(stampedeId, distBuildService))) {
      int buildExitCode =
          localBuildExecutor.buildLocallyAndReturnExitCode(
              topLevelTargetsToBuild, Optional.empty());
      setter.setFinalBuildStatus(buildExitCode);
      return buildExitCode;
    }
  }
}
