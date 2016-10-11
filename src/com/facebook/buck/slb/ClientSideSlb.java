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
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ClientSideSlb implements HttpLoadBalancer {

  private final String pingEndpoint;
  private final ImmutableList<URI> serverPool;
  private final OkHttpClient pingClient;
  private final ServerHealthManager healthManager;
  private final Clock clock;
  private final ScheduledExecutorService schedulerService;
  private final ScheduledFuture<?> backgroundHealthChecker;
  private final BuckEventBus eventBus;

  public static boolean isSafeToCreate(ClientSideSlbConfig config) {
    return config.getPingEndpoint() != null &&
        config.getServerPool() != null &&
        config.getServerPool().size() > 0 &&
        config.getEventBus() != null;
  }

  // Use the Builder.
  public ClientSideSlb(ClientSideSlbConfig config) {
    this(config, new OkHttpClient.Builder()
        .connectTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
        .writeTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
        .build());
  }

  @VisibleForTesting
  ClientSideSlb(ClientSideSlbConfig config, OkHttpClient pingClient) {
    this.clock = config.getClock();
    this.pingEndpoint = Preconditions.checkNotNull(config.getPingEndpoint());
    this.serverPool = Preconditions.checkNotNull(config.getServerPool());
    this.eventBus = Preconditions.checkNotNull(config.getEventBus());
    Preconditions.checkArgument(serverPool.size() > 0, "No server URLs passed.");

    this.healthManager = new ServerHealthManager(
        this.serverPool,
        config.getErrorCheckTimeRangeMillis(),
        config.getMaxErrorPercentage(),
        config.getLatencyCheckTimeRangeMillis(),
        config.getMaxAcceptableLatencyMillis(),
        config.getEventBus());
    this.pingClient = pingClient;

    this.schedulerService = config.getSchedulerService();
    backgroundHealthChecker = this.schedulerService.scheduleWithFixedDelay(
        this::backgroundThreadCallForHealthCheck,
        0,
        config.getHealthCheckIntervalMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public URI getBestServer() throws NoHealthyServersException {
    return healthManager.getBestServer(clock.currentTimeMillis());
  }

  @Override
  public void reportRequestSuccess(URI server) {
    healthManager.reportRequestSuccess(server, clock.currentTimeMillis());
  }

  @Override
  public void reportRequestException(URI server) {
    healthManager.reportRequestError(server, clock.currentTimeMillis());
  }

  @Override
  public void close() {
    backgroundHealthChecker.cancel(true);
  }

  // TODO(ruibm): Register for BuildStart events in the EventBus and force a health check then.
  // TODO(ruibm): Log into timeseries information about each run.
  // TODO(ruibm): Add cache health information to the SuperConsole.
  private void backgroundThreadCallForHealthCheck() {
    LoadBalancerPingEventData.Builder data = LoadBalancerPingEventData.builder();
    for (URI serverUri : serverPool) {
      PerServerPingData.Builder perServerData = PerServerPingData.builder().setServer(serverUri);
      Request request =
          new Request.Builder()
              .url(serverUri.resolve(pingEndpoint).toString())
              .get()
              .build();
      long nowMillis = clock.currentTimeMillis();
      Stopwatch stopwatch = Stopwatch.createStarted();
      try {
        Response response = pingClient.newCall(request).execute();
        try {
          // Make sure we explicitly read the whole response and that's taken into account in
          // the latency calculation.
          try (InputStream inputStream = response.body().byteStream()) {
            ByteStreams.copy(inputStream, ByteStreams.nullOutputStream());
          }
          long requestLatencyMillis = stopwatch.elapsed(TimeUnit.MILLISECONDS);
          perServerData.setPingRequestLatencyMillis(requestLatencyMillis);
          healthManager.reportPingLatency(serverUri, nowMillis, requestLatencyMillis);
          healthManager.reportRequestSuccess(serverUri, nowMillis);
        } finally {
          // This guarantees response resources are released. In OkHttp if the Response's stream
          // is not explicitly closed the connection is leaked from the connection pool and
          // it will go into CLOSE_WAIT state waiting for a TCP timeout to be hit.
          response.body().close();
        }
      } catch (IOException e) {
        healthManager.reportRequestError(serverUri, nowMillis);
        perServerData.setException(e);
      } finally {
        data.addPerServerData(perServerData.build());
      }
    }

    eventBus.post(new LoadBalancerPingEvent(data.build()));
  }
}
