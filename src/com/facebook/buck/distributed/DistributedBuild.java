/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEventBus;

// TODO(ruibm): Currently this class only implements dummy behaviour to mock the distbuild.
public class DistributedBuild {
  private static final int TOTAL_JOBS = 648;

  private final DistBuildService distBuildService;
  private final BuckEventBus eventBus;

  public DistributedBuild(DistBuildService distBuildService, BuckEventBus eventBus) {
    this.eventBus = eventBus;
    this.distBuildService = distBuildService;
  }

  public int executeAndPrintFailuresToEventBus() {
    int jobsDone = 0;
    while (jobsDone < TOTAL_JOBS) {
      distBuildService.submitJob();
      DistBuildStatus status = DistBuildStatus.builder()
          .setPercentProgress(((float) jobsDone) / TOTAL_JOBS)
          .build();
      eventBus.post(new DistBuildStatusEvent(status));
      jobsDone += TOTAL_JOBS / 3;
    }

    return 0;
  }
}
