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

package com.facebook.buck.simulate;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.model.BuildTarget;

import java.util.concurrent.TimeUnit;

public class SimulateEvent extends AbstractBuckEvent {
  private static final long THREAD_ID_OFFSET = 4200;

  private final BuildSimulator.SimulationNode node;
  private final long simulationMillis;

  private SimulateEvent(BuildSimulator.SimulationNode node, long simulationMillis) {
    super(EventKey.slowValueKey(node.getTarget()));
    this.node = node;
    this.simulationMillis = simulationMillis;
  }

  @Override
  protected String getValueString() {
    return getTarget().toString();
  }

  @Override
  public String getEventName() {
    return "simulated_time";
  }

  @Override
  public long getThreadId() {
    // We add this constant offset to make sure it doesn't overlap with the system threadIds thus
    // looking nicer in the Chrome Tracing viewer.
    return node.getThreadId() + THREAD_ID_OFFSET;
  }

  public BuildTarget getTarget() {
    return node.getTarget();
  }

  @Override
  public long getTimestamp() {
    return simulationMillis;
  }

  @Override
  public long getNanoTime() {
    return TimeUnit.MILLISECONDS.toNanos(simulationMillis);
  }

  public static class Started extends SimulateEvent {
    public Started(BuildSimulator.SimulationNode node, long simulationMillis) {
      super(node, simulationMillis);
    }
  }

  public static class Finished extends SimulateEvent {
    public Finished(BuildSimulator.SimulationNode node, long simulationMillis) {
      super(node, simulationMillis);
    }
  }
}
