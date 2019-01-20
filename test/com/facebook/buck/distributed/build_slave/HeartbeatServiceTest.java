/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.distributed.build_slave.HeartbeatService.HeartbeatCallback;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HeartbeatServiceTest {

  private static final long INTERVAL_MILLIS = 42;

  private ScheduledExecutorService mockExecutor;
  private Capture<Runnable> runnable;

  @Before
  public void setUp() {
    mockExecutor = EasyMock.createMock(ScheduledExecutorService.class);
    runnable = EasyMock.newCapture();
    EasyMock.expect(
            mockExecutor.scheduleAtFixedRate(
                EasyMock.capture(runnable),
                EasyMock.anyLong(),
                EasyMock.eq(INTERVAL_MILLIS),
                EasyMock.anyObject(TimeUnit.class)))
        .andReturn(null)
        .once();
    mockExecutor.shutdown();
    EasyMock.expectLastCall().once();
    EasyMock.replay(mockExecutor);
  }

  @After
  public void tearDown() {
    EasyMock.verify(mockExecutor);
  }

  @Test
  public void testCallbackIsCalled() {
    AtomicInteger callCount = new AtomicInteger();
    HeartbeatCallback callback = () -> callCount.incrementAndGet();
    try (HeartbeatService service = createService(callback)) {
      runnable.getValue().run();
      Assert.assertEquals(1, callCount.get());
    }
  }

  @Test
  public void testCallbackKeepsBeingCalledAfterIOException() {
    AtomicInteger callCount = new AtomicInteger();
    HeartbeatCallback callback =
        () -> {
          callCount.incrementAndGet();
          throw new IOException("Going down... or not!!!");
        };
    try (HeartbeatService service = createService(callback)) {
      runnable.getValue().run();
      runnable.getValue().run();
      Assert.assertEquals(2, callCount.get());
    }
  }

  private HeartbeatService createService(HeartbeatCallback callback) {
    HeartbeatService service = new HeartbeatService(INTERVAL_MILLIS, mockExecutor);
    service.addCallback("testCallback", callback);
    return service;
  }
}
