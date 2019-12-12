/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.counters;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.google.common.collect.ImmutableList;

public class CountersSnapshotEvent extends AbstractBuckEvent {

  private final ImmutableList<CounterSnapshot> snapshots;

  public CountersSnapshotEvent(Iterable<CounterSnapshot> snapshots) {
    super(EventKey.unique());
    this.snapshots = ImmutableList.copyOf(snapshots);
  }

  public ImmutableList<CounterSnapshot> getSnapshots() {
    return snapshots;
  }

  @Override
  public String getEventName() {
    return "CountersSnapshot";
  }

  @Override
  protected String getValueString() {
    return String.format("%s with [%d] counters.", getEventName(), snapshots.size());
  }
}
