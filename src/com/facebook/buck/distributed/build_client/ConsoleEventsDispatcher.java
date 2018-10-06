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
package com.facebook.buck.distributed.build_client;

import com.facebook.buck.distributed.DistBuildStatus;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/** Helper class for dispatching remote events (related to console updates) locally. */
public class ConsoleEventsDispatcher {

  private final BuckEventBus buckEventBus;

  public ConsoleEventsDispatcher(BuckEventBus buckEventBus) {
    this.buckEventBus = buckEventBus;
  }

  public void postDistBuildProgressEvent(CoordinatorBuildProgress buildProgress) {
    buckEventBus.post(new DistBuildRemoteProgressEvent(Objects.requireNonNull(buildProgress)));
  }

  public void postDistBuildStatusEvent(BuildJob job, List<BuildSlaveStatus> slaveStatuses) {
    postDistBuildStatusEvent(job, slaveStatuses, null);
  }

  /** Posts the event to the event bus. */
  public void postDistBuildStatusEvent(
      BuildJob job, List<BuildSlaveStatus> slaveStatuses, @Nullable String statusOverride) {

    Optional<String> stage = Optional.empty();
    if (statusOverride != null) {
      stage = Optional.of(statusOverride);
    } else if (!job.getStatus().equals(BuildStatus.BUILDING)) {
      stage = Optional.of(job.getStatus().toString());
    }

    DistBuildStatus status =
        DistBuildStatus.builder().setStatus(stage).setSlaveStatuses(slaveStatuses).build();
    buckEventBus.post(new DistBuildStatusEvent(job, status));
  }

  public BuckEventBus getBuckEventBus() {
    return buckEventBus;
  }

  public void sendBuildFinishedEvent(BuildSlaveStats buildSlaveStats) {
    buckEventBus.post(new ClientSideBuildSlaveFinishedStatsEvent(buildSlaveStats));
  }

  public void postConsoleEvent(ConsoleEvent event) {
    buckEventBus.post(event);
  }
}
