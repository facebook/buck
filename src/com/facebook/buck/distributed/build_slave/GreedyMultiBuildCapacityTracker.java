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

import com.google.common.base.Preconditions;
import java.io.IOException;

/**
 * Implementation of {@link AbstractMultiBuildCapacityTracker} that obtains all available capacity
 * when reserving work and returns unused capacity when committing work.
 */
public class GreedyMultiBuildCapacityTracker extends AbstractMultiBuildCapacityTracker {

  protected int obtainedCapacity;

  public GreedyMultiBuildCapacityTracker(CapacityService service, int maxAvailableCapacity) {
    super(service, maxAvailableCapacity);
  }

  @Override
  public synchronized int reserveAllAvailableCapacity() {
    int capacity = maxAvailableCapacity;
    try {
      capacity = service.obtainAllAvailableCapacity();
    } catch (IOException e) {
      LOG.warn(e, "Failed obtaining capacity. Using max available.");
    }
    obtainedCapacity += capacity;
    return capacity;
  }

  @Override
  public synchronized void commitCapacity(int numWorkUnits) {
    Preconditions.checkArgument(numWorkUnits >= 0);
    int capacityToReturn = obtainedCapacity - numWorkUnits;
    if (capacityToReturn > 0) {
      LOG.info(
          String.format(
              "Got [%d] workUnits. Returning [%d] capacity.", numWorkUnits, capacityToReturn));
      returnCapacity(capacityToReturn);
    }
    obtainedCapacity = 0;
  }
}
