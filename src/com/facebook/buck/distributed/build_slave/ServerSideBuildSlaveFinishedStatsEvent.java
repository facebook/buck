/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.distributed.thrift.BuildSlaveFinishedStats;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import java.util.Optional;

/** Published at the end of a build slaves run. */
public class ServerSideBuildSlaveFinishedStatsEvent extends AbstractBuckEvent {
  private final StampedeId stampedeId;
  private final BuildSlaveRunId runId;
  private final Optional<String> buildLabel;
  private final String minionType;
  private final BuildSlaveFinishedStats buildSlaveFinishedStats;

  public ServerSideBuildSlaveFinishedStatsEvent(
      StampedeId stampedeId,
      BuildSlaveRunId runId,
      Optional<String> buildLabel,
      String minionType,
      BuildSlaveFinishedStats buildSlaveFinishedStats) {
    super(EventKey.unique());
    this.stampedeId = stampedeId;
    this.runId = runId;
    this.buildLabel = buildLabel;
    this.minionType = minionType;
    this.buildSlaveFinishedStats = buildSlaveFinishedStats;
  }

  @Override
  protected String getValueString() {
    return getEventName();
  }

  @Override
  public String getEventName() {
    return this.getClass().getName();
  }

  public BuildSlaveFinishedStats getBuildSlaveFinishedStats() {
    return buildSlaveFinishedStats;
  }

  public StampedeId getStampedeId() {
    return stampedeId;
  }

  public BuildSlaveRunId getRunId() {
    return runId;
  }

  public Optional<String> getBuildLabel() {
    return buildLabel;
  }

  public String getMinionType() {
    return minionType;
  }
}
