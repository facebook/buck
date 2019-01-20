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
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DefaultMultiBuildCapacityTrackerTest {

  public static final int MAX_AVAILABLE_CAPACITY = 10;
  public static final int CAPACITY = 4;

  private CapacityService mockService;
  private DefaultMultiBuildCapacityTracker capacityTracker;

  @Before
  public void setUp() {
    mockService = EasyMock.createMock(CapacityService.class);
    capacityTracker = new DefaultMultiBuildCapacityTracker(mockService, MAX_AVAILABLE_CAPACITY);
  }

  @Test
  public void testReserveGetsCapacity() throws IOException {
    EasyMock.expect(mockService.getAllAvailableCapacity()).andReturn(CAPACITY).once();
    EasyMock.replay(mockService);

    int availableCapacity = capacityTracker.reserveAllAvailableCapacity();
    Assert.assertEquals(CAPACITY, availableCapacity);

    EasyMock.verify(mockService);
  }

  @Test
  public void testReserveHandlesException() throws IOException {
    EasyMock.expect(mockService.getAllAvailableCapacity()).andThrow(new IOException()).once();
    EasyMock.replay(mockService);

    int availableCapacity = capacityTracker.reserveAllAvailableCapacity();
    Assert.assertEquals(MAX_AVAILABLE_CAPACITY, availableCapacity);

    EasyMock.verify(mockService);
  }

  @Test
  public void testCommitObtainsCapacity() {
    mockService.obtainCapacity(CAPACITY);
    EasyMock.expectLastCall().once();
    EasyMock.replay(mockService);

    capacityTracker.commitCapacity(CAPACITY);

    EasyMock.verify(mockService);
  }
}
