/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.slb;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.net.URI;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ServerHealthManagerTest {
  private static final ImmutableList<URI> SERVERS =
      ImmutableList.of(
          URI.create("http://localhost:4242"),
          URI.create("http://localhost:8484"),
          URI.create("http://localhost:2121"));

  private static final long NOW_MILLIS = 1409702151000L;
  private static final long NOW_NANO_TIME = 3000000;
  private static final FakeClock NOW_FAKE_CLOCK =
      FakeClock.builder().currentTimeMillis(NOW_MILLIS).nanoTime(NOW_NANO_TIME).build();
  private static final int RANGE_MILLIS = 42;
  private static final float MAX_ERROR_PERCENTAGE = 0.1f;
  private static final int MAX_ACCEPTABLE_LATENCY_MILLIS = 42;

  private BuckEventBus eventBus;

  @Before
  public void setUp() {
    eventBus = BuckEventBusForTests.newInstance();
  }

  @Test
  public void testGetBestServerWithoutInformation() throws IOException {
    ServerHealthManager manager = newServerHealthManager();
    URI server = manager.getBestServer();
    Assert.assertNotNull(server);
  }

  @Test(expected = NoHealthyServersException.class)
  public void testExceptionThrownIfServersAreUnhealthy() throws IOException {
    ServerHealthManager manager = newServerHealthManager();
    reportErrorToAll(manager, 1);
    manager.getBestServer();
    Assert.fail("All servers have errors so an exception was expected.");
  }

  @Test(expected = NoHealthyServersException.class)
  public void testExceptionThrownIfServersAreTooSlow() throws IOException {
    ServerHealthManager manager = newServerHealthManager();
    reportLatencyToAll(manager, MAX_ACCEPTABLE_LATENCY_MILLIS + 1);
    manager.getBestServer();
    Assert.fail("All servers have high latency so an exception was expected.");
  }

  @Test
  public void testFastestServerIsAlwaysReturned() throws IOException {
    ServerHealthManager manager = newServerHealthManager();
    for (int i = 0; i < SERVERS.size(); ++i) {
      manager.reportPingLatency(SERVERS.get(i), i);
    }

    URI server = manager.getBestServer();
    Assert.assertEquals(SERVERS.get(0), server);
  }

  private void reportLatencyToAll(ServerHealthManager manager, int latencyMillis) {
    for (URI server : SERVERS) {
      manager.reportPingLatency(server, latencyMillis);
    }
  }

  private ServerHealthManager newServerHealthManager() {
    return new ServerHealthManager(
        SERVERS,
        RANGE_MILLIS,
        MAX_ERROR_PERCENTAGE,
        RANGE_MILLIS,
        MAX_ACCEPTABLE_LATENCY_MILLIS,
        eventBus,
        NOW_FAKE_CLOCK);
  }

  private static void reportErrorToAll(ServerHealthManager manager, int numberOfErrors) {
    for (int i = 0; i < numberOfErrors; ++i) {
      for (URI server : SERVERS) {
        manager.reportRequestError(server);
      }
    }
  }
}
