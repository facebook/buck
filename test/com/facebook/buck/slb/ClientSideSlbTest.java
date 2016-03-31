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
import com.facebook.buck.timing.Clock;
import com.google.common.collect.ImmutableList;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ClientSideSlbTest {

  private static final ImmutableList<URI> SERVERS = ImmutableList.of(
      URI.create("http://localhost:4242"),
      URI.create("http://localhost:8484"),
      URI.create("http://localhost:2121")
  );

  private BuckEventBus mockBus;
  private Clock mockClock;
  private OkHttpClient mockClient;
  private ScheduledExecutorService mockScheduler;
  private ClientSideSlbConfig config;

  // Apparently EasyMock does not deal very well with Generic Types using wildcard ?.
  // Several workarounds can be found on StackOverflow this one being the list intrusive.
  @SuppressWarnings("rawtypes")
  private ScheduledFuture mockFuture;

  @Before
  public void setUp() {
    mockBus = EasyMock.createNiceMock(BuckEventBus.class);
    mockFuture = EasyMock.createMock(ScheduledFuture.class);
    mockClient = EasyMock.createNiceMock(OkHttpClient.class);
    mockScheduler = EasyMock.createMock(ScheduledExecutorService.class);
    mockClock = EasyMock.createMock(Clock.class);
    EasyMock.expect(mockClock.currentTimeMillis()).andReturn(42L).anyTimes();
    EasyMock.replay(mockClock);

    config = ClientSideSlbConfig.builder()
        .setClock(mockClock)
        .setSchedulerService(mockScheduler)
        .setPingHttpClient(mockClient)
        .setServerPool(SERVERS)
        .setEventBus(mockBus)
        .build();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testBackgroundHealthCheckIsScheduled() {
    Capture<Runnable> capture = EasyMock.newCapture();
    EasyMock.expect(mockScheduler.scheduleWithFixedDelay(
        EasyMock.capture(capture),
        EasyMock.anyLong(),
        EasyMock.anyLong(),
        EasyMock.anyObject(TimeUnit.class)))
        .andReturn(mockFuture)
        .once();
    EasyMock.replay(mockScheduler);

    try (ClientSideSlb slb = new ClientSideSlb(config)) {
      Assert.assertTrue(capture.hasCaptured());
    }

    EasyMock.verify(mockScheduler);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testAllServersArePinged() throws IOException {
    Capture<Runnable> capture = EasyMock.newCapture();
    EasyMock.expect(mockScheduler.scheduleWithFixedDelay(
        EasyMock.capture(capture),
        EasyMock.anyLong(),
        EasyMock.anyLong(),
        EasyMock.anyObject(TimeUnit.class)))
        .andReturn(mockFuture)
        .once();
    ResponseBody body = ResponseBody.create(MediaType.parse("text/plain"), "The Body.");
    Response response = new Response.Builder()
        .body(body)
        .code(200)
        .protocol(Protocol.HTTP_1_1)
        .request(new Request.Builder().url("http://dummy.url").build())
        .build();
    Call mockCall = EasyMock.createMock(Call.class);
    EasyMock.expect(mockCall.execute()).andReturn(response).times(SERVERS.size());
    EasyMock.expect(mockClient.newCall(EasyMock.anyObject(Request.class)))
        .andReturn(mockCall)
        .times(SERVERS.size());
    EasyMock.replay(mockClient, mockCall, mockScheduler);

    try (ClientSideSlb slb = new ClientSideSlb(config)) {
      Runnable healthCheckLoop = capture.getValue();
      healthCheckLoop.run();
    }

    EasyMock.verify(mockClient, mockCall, mockScheduler);
  }
}
