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

import java.io.IOException;

/**
 * Implementation of {@link AbstractMultiBuildCapacityTracker} that obtains capacity when committing
 * work.
 */
public class DefaultMultiBuildCapacityTracker extends AbstractMultiBuildCapacityTracker {

  public DefaultMultiBuildCapacityTracker(CapacityService service, int maxAvailableCapacity) {
    super(service, maxAvailableCapacity);
  }

  @Override
  public synchronized int reserveAllAvailableCapacity() {
    int availableCapacity = maxAvailableCapacity;
    try {
      availableCapacity = service.getAllAvailableCapacity();
    } catch (IOException e) {
      LOG.warn(e, "Returning max available capacity.");
    }
    return availableCapacity;
  }

  @Override
  public synchronized void commitCapacity(int numWorkUnits) {
    if (numWorkUnits == 0) {
      return;
    }
    service.obtainCapacity(numWorkUnits);
  }
}
