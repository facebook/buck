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

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SingleBuildCapacityTrackerTest {

  public static final int MAX_AVAILABLE_CAPACITY = 10;
  public static final int NUM_WORK_UNITS = 5;
  public static final int REMAINING_CAPACITY = 5;

  private SingleBuildCapacityTracker capacityTracker;

  @Before
  public void setUp() {
    capacityTracker = new SingleBuildCapacityTracker(MAX_AVAILABLE_CAPACITY);
  }

  @Test
  public void testReserveAvailableCapacity() {
    int availableCapacity = capacityTracker.reserveAllAvailableCapacity();
    Assert.assertEquals(MAX_AVAILABLE_CAPACITY, availableCapacity);
  }

  @Test
  public void testReturnUnusedCapacity() {
    // Reserve all available capacity
    capacityTracker.reserveAllAvailableCapacity();
    // Return unused capacity
    capacityTracker.commitCapacity(NUM_WORK_UNITS);

    int availableCapacity = capacityTracker.reserveAllAvailableCapacity();
    Assert.assertEquals(REMAINING_CAPACITY, availableCapacity);
  }

  @Test
  public void testReturnCapacityAfterWorkUnitBuilkt() {
    int availableCapacity = capacityTracker.reserveAllAvailableCapacity();
    Assert.assertEquals(MAX_AVAILABLE_CAPACITY, availableCapacity);

    // Let know we started building
    capacityTracker.commitCapacity(NUM_WORK_UNITS);

    // Let know build is finished
    capacityTracker.returnCapacity();
    availableCapacity = capacityTracker.reserveAllAvailableCapacity();

    // Now we have capacity for remaining + 1 work unit
    Assert.assertEquals(REMAINING_CAPACITY + 1, availableCapacity);
  }
}
