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

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.immutables.value.Value;

public class DistBuildStatusEvent extends AbstractBuckEvent implements LeafEvent, WorkAdvanceEvent {

  private final DistBuildStatus status;

  public DistBuildStatusEvent(DistBuildStatus status) {
    super(EventKey.unique());
    this.status = status;
  }

  public DistBuildStatus getStatus() {
    return status;
  }

  @Override
  protected String getValueString() {
    return getEventName();
  }

  @Override
  public String getEventName() {
    return this.getClass().getName();
  }

  @Override
  public String getCategory() {
    return this.getClass().getName();
  }

  // TODO(ruibm): This will be replaced with whatever thrift message the server returns.
  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractDistBuildStatus {
    /** @return dist-build status */
    abstract Optional<String> getStatus();

    /** @return the status of each build slave */
    abstract ImmutableList<BuildSlaveStatus> getSlaveStatuses();
  }
}
