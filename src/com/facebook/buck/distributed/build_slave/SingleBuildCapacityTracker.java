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

import javax.annotation.concurrent.ThreadSafe;

/** Used when there's only one build running on the host */
@ThreadSafe
public class SingleBuildCapacityTracker implements CapacityTracker {

  private final int maxAvailableCapacity;
  private int availableCapacity;

  public SingleBuildCapacityTracker(int maxAvailableCapacity) {
    this.maxAvailableCapacity = maxAvailableCapacity;
    this.availableCapacity = maxAvailableCapacity;
  }

  @Override
  public int getMaxAvailableCapacity() {
    return maxAvailableCapacity;
  }

  @Override
  public synchronized int reserveAllAvailableCapacity() {
    return availableCapacity;
  }

  @Override
  public synchronized void commitCapacity(int numWorkUnits) {
    availableCapacity -= numWorkUnits;
  }

  @Override
  public synchronized void returnCapacity() {
    availableCapacity++;
  }
}
