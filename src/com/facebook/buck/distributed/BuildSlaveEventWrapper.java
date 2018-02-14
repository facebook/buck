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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;

/** Contains the BuildSlaveEvent plus extra metadata associated with the event. */
public class BuildSlaveEventWrapper {
  private int eventNumber;
  private BuildSlaveRunId buildSlaveRunId;
  private BuildSlaveEvent event;

  public BuildSlaveEventWrapper(
      int eventNumber, BuildSlaveRunId buildSlaveRunId, BuildSlaveEvent event) {
    this.eventNumber = eventNumber;
    this.buildSlaveRunId = buildSlaveRunId;
    this.event = event;
  }

  public int getEventNumber() {
    return eventNumber;
  }

  public BuildSlaveRunId getBuildSlaveRunId() {
    return buildSlaveRunId;
  }

  public BuildSlaveEvent getEvent() {
    return event;
  }
}
