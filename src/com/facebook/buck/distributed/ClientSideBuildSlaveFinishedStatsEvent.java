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

import com.facebook.buck.distributed.thrift.BuildSlaveFinishedStats;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import java.util.List;

public class ClientSideBuildSlaveFinishedStatsEvent extends AbstractBuckEvent {
  private final List<BuildSlaveFinishedStats> buildSlaveFinishedStats;

  public ClientSideBuildSlaveFinishedStatsEvent(
      List<BuildSlaveFinishedStats> buildSlaveFinishedStats) {
    super(EventKey.unique());
    this.buildSlaveFinishedStats = buildSlaveFinishedStats;
  }

  @Override
  public String getValueString() {
    return getEventName();
  }

  @Override
  public String getEventName() {
    return this.getClass().getName();
  }

  public List<BuildSlaveFinishedStats> getBuildSlaveFinishedStats() {
    return buildSlaveFinishedStats;
  }
}
