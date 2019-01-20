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

public class GreedyMultiBuildCapacityTrackerTest {

  public static final int MAX_AVAILABLE_CAPACITY = 10;
  public static final int CAPACITY = 4;

  private CapacityService mockService;
  private GreedyMultiBuildCapacityTracker capacityTracker;

  @Before
  public void setUp() {
    mockService = EasyMock.createMock(CapacityService.class);
    capacityTracker = new GreedyMultiBuildCapacityTracker(mockService, MAX_AVAILABLE_CAPACITY);
  }

  @Test
  public void testReserveObtainsCapacity() throws IOException {
    EasyMock.expect(mockService.obtainAllAvailableCapacity()).andReturn(CAPACITY).once();
    EasyMock.replay(mockService);

    int availableCapacity = capacityTracker.reserveAllAvailableCapacity();
    Assert.assertEquals(CAPACITY, availableCapacity);

    EasyMock.verify(mockService);
  }

  @Test
  public void testReserveHandlesException() throws IOException {
    EasyMock.expect(mockService.obtainAllAvailableCapacity()).andThrow(new IOException()).once();
    EasyMock.replay(mockService);

    int availableCapacity = capacityTracker.reserveAllAvailableCapacity();
    Assert.assertEquals(MAX_AVAILABLE_CAPACITY, availableCapacity);

    EasyMock.verify(mockService);
  }

  @Test
  public void testCommitReturnsUnusedCapacity() throws IOException {
    // reserve more capacity than to be committed (+1)
    EasyMock.expect(mockService.obtainAllAvailableCapacity()).andReturn(CAPACITY + 1).once();
    // return unused (1) capacity
    mockService.returnCapacity(1);
    EasyMock.expectLastCall().once();
    EasyMock.replay(mockService);

    capacityTracker.reserveAllAvailableCapacity();
    capacityTracker.commitCapacity(CAPACITY);

    EasyMock.verify(mockService);
  }

  @Test
  public void testCommitWithoutReserve() throws IOException {
    mockService.returnCapacity(EasyMock.anyInt());
    EasyMock.expectLastCall().andThrow(new AssertionError()).anyTimes();
    EasyMock.replay(mockService);

    capacityTracker.commitCapacity(EasyMock.anyInt());

    EasyMock.verify(mockService);
  }

  @Test
  public void testReturnSucceess() throws IOException {
    mockService.returnCapacity(CAPACITY);
    EasyMock.expectLastCall().once();
    EasyMock.replay(mockService);

    boolean success = capacityTracker.returnCapacity(CAPACITY);
    Assert.assertTrue(success);

    EasyMock.verify(mockService);
  }

  @Test
  public void testReturnFailure() throws IOException {
    mockService.returnCapacity(EasyMock.anyInt());
    EasyMock.expectLastCall().andThrow(new IOException());
    EasyMock.replay(mockService);

    boolean success = capacityTracker.returnCapacity(CAPACITY);
    Assert.assertFalse(success);

    EasyMock.verify(mockService);
  }
}
