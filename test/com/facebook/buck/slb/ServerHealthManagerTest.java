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
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;

public class ServerHealthManagerTest {
  private static final ImmutableList<URI> SERVERS = ImmutableList.of(
      URI.create("http://localhost:4242"),
      URI.create("http://localhost:8484"),
      URI.create("http://localhost:2121")
  );

  private static final long NOW_MILLIS = 0;
  private static final int RANGE_MILLIS = 42;
  private static final float MAX_ERRORS_PER_SECOND = 1;
  private static final int MAX_ACCEPTABLE_LATENCY_MILLIS = 42;

  private BuckEventBus eventBus;

  @Before
  public void setUp() {
    eventBus = EasyMock.createNiceMock(BuckEventBus.class);
  }

  @Test
  public void testGetBestServerWithoutInformation() throws IOException {
    ServerHealthManager manager = newServerHealthManager();
    URI server = manager.getBestServer(NOW_MILLIS);
    Assert.assertNotNull(server);
  }

  @Test(expected = IOException.class)
  public void testExceptionThrownIfServersAreUnhealthy() throws IOException {
    ServerHealthManager manager = newServerHealthManager();
    reportErrorToAll(manager, MAX_ACCEPTABLE_LATENCY_MILLIS * RANGE_MILLIS + 1);
    manager.getBestServer(NOW_MILLIS);
    Assert.fail("All servers have errors so an exception was expected.");
  }

  @Test(expected = IOException.class)
  public void testExceptionThrownIfServersAreTooSlow() throws IOException {
    ServerHealthManager manager = newServerHealthManager();
    reportErrorToAll(manager, MAX_ACCEPTABLE_LATENCY_MILLIS * RANGE_MILLIS + 1);
    reportLatencyToAll(manager, MAX_ACCEPTABLE_LATENCY_MILLIS + 1);
    manager.getBestServer(NOW_MILLIS);
    Assert.fail("All servers have high latency so an exception was expected.");
  }

  @Test
  public void testFastestServerIsAlwaysReturned() throws IOException {
    ServerHealthManager manager = newServerHealthManager();
    for (int i = 0; i < SERVERS.size(); ++i) {
      manager.reportLatency(SERVERS.get(i), NOW_MILLIS, i);
    }

    URI server = manager.getBestServer(NOW_MILLIS);
    Assert.assertEquals(SERVERS.get(0), server);
  }

  private void reportLatencyToAll(ServerHealthManager manager, int latencyMillis) {
    for (URI server : SERVERS) {
      manager.reportLatency(server, NOW_MILLIS, latencyMillis);
    }
  }

  private ServerHealthManager newServerHealthManager() {
    return new ServerHealthManager(
        SERVERS,
        RANGE_MILLIS,
        MAX_ERRORS_PER_SECOND,
        RANGE_MILLIS,
        MAX_ACCEPTABLE_LATENCY_MILLIS,
        eventBus);
  }

  private static void reportErrorToAll(ServerHealthManager manager, int numberOfErrors) {
    for (int i = 0; i < numberOfErrors; ++i) {
      for (URI server : SERVERS) {
        manager.reportError(server, NOW_MILLIS);
      }
    }
  }
}
