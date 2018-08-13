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
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableList;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ClientSideSlbTest extends EasyMockSupport {

  private static final ImmutableList<URI> SERVERS =
      ImmutableList.of(
          URI.create("http://localhost:4242"),
          URI.create("http://localhost:8484"),
          URI.create("http://localhost:2121"));
  private static final String SERVER_POOL_NAME = ClientSideSlbTest.class + "_server_pool";

  private BuckEventBus mockBus;
  private Clock mockClock;
  private OkHttpClient mockClient;
  private ScheduledExecutorService mockScheduler;
  private ClientSideSlbConfig config;
  private Dispatcher dispatcher;

  // Apparently EasyMock does not deal very well with Generic Types using wildcard ?.
  // Several workarounds can be found on StackOverflow this one being the list intrusive.
  @SuppressWarnings("rawtypes")
  private ScheduledFuture mockFuture;

  @Before
  public void setUp() {
    mockBus = createNiceMock(BuckEventBus.class);
    mockFuture = createMock(ScheduledFuture.class);
    mockClient = createNiceMock(OkHttpClient.class);
    dispatcher = new Dispatcher(createMock(ExecutorService.class));
    EasyMock.expect(mockClient.dispatcher()).andReturn(dispatcher).anyTimes();
    mockScheduler = createMock(ScheduledExecutorService.class);
    mockClock = createMock(Clock.class);
    EasyMock.expect(mockClock.currentTimeMillis()).andReturn(42L).anyTimes();

    config =
        ClientSideSlbConfig.builder()
            .setClock(mockClock)
            .setServerPool(SERVERS)
            .setEventBus(mockBus)
            .setServerPoolName(SERVER_POOL_NAME)
            .build();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testBackgroundHealthCheckIsScheduled() {
    Capture<Runnable> capture = EasyMock.newCapture();
    EasyMock.expect(
            mockScheduler.scheduleWithFixedDelay(
                EasyMock.capture(capture),
                EasyMock.anyLong(),
                EasyMock.anyLong(),
                EasyMock.anyObject(TimeUnit.class)))
        .andReturn(mockFuture)
        .once();
    EasyMock.expect(mockFuture.cancel(true)).andReturn(true).once();
    EasyMock.expect(mockScheduler.shutdownNow()).andReturn(ImmutableList.of()).once();
    EasyMock.expect(dispatcher.executorService().shutdownNow())
        .andReturn(ImmutableList.of())
        .once();

    replayAll();

    try (ClientSideSlb slb = new ClientSideSlb(config, mockScheduler, mockClient)) {
      Assert.assertTrue(capture.hasCaptured());
    }

    verifyAll();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testAllServersArePinged() {
    Capture<Runnable> capture = EasyMock.newCapture();
    EasyMock.expect(
            mockScheduler.scheduleWithFixedDelay(
                EasyMock.capture(capture),
                EasyMock.anyLong(),
                EasyMock.anyLong(),
                EasyMock.anyObject(TimeUnit.class)))
        .andReturn(mockFuture)
        .once();
    Call mockCall = createMock(Call.class);
    for (URI server : SERVERS) {
      EasyMock.expect(mockClient.newCall(EasyMock.anyObject(Request.class))).andReturn(mockCall);
      mockCall.enqueue(EasyMock.anyObject(ClientSideSlb.ServerPing.class));
      EasyMock.expectLastCall()
          .andAnswer(
              new IAnswer<Object>() {
                @Override
                public Object answer() throws Throwable {
                  Callback callback = (Callback) EasyMock.getCurrentArguments()[0];
                  ResponseBody body =
                      ResponseBody.create(MediaType.parse("text/plain"), "The Body.");
                  Response response =
                      new Response.Builder()
                          .body(body)
                          .code(200)
                          .protocol(Protocol.HTTP_1_1)
                          .request(new Request.Builder().url(server.toString()).build())
                          .message("")
                          .build();
                  callback.onResponse(mockCall, response);
                  return null;
                }
              });
    }
    mockBus.post(EasyMock.anyObject(LoadBalancerPingEvent.class));
    EasyMock.expectLastCall();
    EasyMock.expect(mockFuture.cancel(true)).andReturn(true).once();
    EasyMock.expect(mockScheduler.shutdownNow()).andReturn(ImmutableList.of()).once();
    EasyMock.expect(dispatcher.executorService().shutdownNow())
        .andReturn(ImmutableList.of())
        .once();

    replayAll();

    try (ClientSideSlb slb = new ClientSideSlb(config, mockScheduler, mockClient)) {
      Runnable healthCheckLoop = capture.getValue();
      healthCheckLoop.run();
    }

    verifyAll();
  }
}
