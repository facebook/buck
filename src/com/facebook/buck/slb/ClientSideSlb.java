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

import com.facebook.buck.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;

import java.io.IOException;
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

  // Use the Builder.
  public ClientSideSlb(ClientSideSlbConfig config) {
    this.clock = config.getClock();
    this.pingEndpoint = Preconditions.checkNotNull(config.getPingEndpoint());
    this.serverPool = Preconditions.checkNotNull(config.getServerPool());
    Preconditions.checkArgument(serverPool.size() > 0, "No server URLs passed.");

    this.healthManager = new ServerHealthManager(
        this.serverPool,
        config.getErrorCheckTimeRangeMillis(),
        config.getMaxErrorsPerSecond(),
        config.getLatencyCheckTimeRangeMillis(),
        config.getMaxAcceptableLatencyMillis());
    this.pingClient = config.getPingHttpClient();
    this.pingClient.setConnectTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS);
    this.pingClient.setReadTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS);
    this.pingClient.setWriteTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS);

    this.schedulerService = config.getSchedulerService();
    backgroundHealthChecker = this.schedulerService.scheduleWithFixedDelay(
        new Runnable() {
          @Override
          public void run() {
            backgroundThreadCallForHealthCheck();
          }
        },
        0,
        config.getHealthCheckIntervalMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public URI getBestServer() throws IOException {
    return healthManager.getBestServer(clock.currentTimeMillis());
  }

  @Override
  public void reportException(URI server) {
    healthManager.reportError(server, clock.currentTimeMillis());
  }

  @Override
  public void close() {
    backgroundHealthChecker.cancel(true);
  }

  // TODO(ruibm): Register for BuildStart events in the EventBus and force a health check then.
  // TODO(ruibm): Log into timeseries information about each run.
  // TODO(ruibm): Add cache health information to the SuperConsole.
  private void backgroundThreadCallForHealthCheck() {
    for (URI uri : serverPool) {
      Request request =
          new Request.Builder()
              .url(uri.resolve(pingEndpoint).toString())
              .get()
              .build();
      long nowMillis = clock.currentTimeMillis();
      Stopwatch stopwatch = Stopwatch.createStarted();
      try {
        pingClient.newCall(request).execute();
        healthManager.reportLatency(uri, nowMillis, stopwatch.elapsed(TimeUnit.MILLISECONDS));
      } catch (IOException e) {
        healthManager.reportError(uri, nowMillis);
      }
    }
  }
}
