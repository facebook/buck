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

package com.facebook.buck.counters;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * Base class for events about building.
 */
public abstract class CountersSnapshotEvent extends AbstractBuckEvent {

  public CountersSnapshotEvent(EventKey eventKey) {
    super(eventKey);
  }

  public static Started started() {
    return new Started();
  }

  public static Finished finished(Started started, ImmutableList<CounterSnapshot> snapshots) {
    return new Finished(started, snapshots);
  }

  public static class Started extends CountersSnapshotEvent {
    protected Started() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "CountersSnapshot.Started";
    }

    @Override
    protected String getValueString() {
      return getEventName();
    }
  }

  public static class Finished extends CountersSnapshotEvent {
    private final ImmutableList<CounterSnapshot> snapshots;

    protected Finished(Started started, ImmutableList<CounterSnapshot> snapshots) {
      super(started.getEventKey());
      this.snapshots = Preconditions.checkNotNull(snapshots);
    }

    public ImmutableList<CounterSnapshot> getSnapshots() {
      return snapshots;
    }

    @Override
    public String getEventName() {
      return "CountersSnapshot.Finished";
    }

    @Override
    protected String getValueString() {
      return String.format("%s with [%d] counters.", getEventName(), snapshots.size());
    }
  }
}
