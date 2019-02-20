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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ClientSideSlb implements HttpLoadBalancer {

  private static final Logger LOG = Logger.get(ClientSideSlb.class);

  private final String pingEndpoint;
  private final ImmutableList<URI> serverPool;
  private final OkHttpClient pingClient;
  private final ServerHealthManager healthManager;
  private final Clock clock;
  private final ScheduledExecutorService schedulerService;
  private final ScheduledFuture<?> backgroundHealthChecker;
  private final BuckEventBus eventBus;

  public static boolean isSafeToCreate(ClientSideSlbConfig config) {
    return config.getPingEndpoint() != null
        && config.getServerPool() != null
        && config.getServerPool().size() > 0
        && config.getEventBus() != null;
  }

  // Use the Builder.
  public ClientSideSlb(ClientSideSlbConfig config, OkHttpClient.Builder clientBuilder) {
    this(
        config,
        Executors.newSingleThreadScheduledExecutor(
            new CommandThreadFactory(
                "ClientSideSlb",
                GlobalStateManager.singleton().getThreadToCommandRegister(),
                Thread.MAX_PRIORITY)),
        clientBuilder
            .dispatcher(
                new Dispatcher(
                    Executors.newCachedThreadPool(
                        new CommandThreadFactory(
                            "ClientSideSlb/OkHttpClient",
                            GlobalStateManager.singleton().getThreadToCommandRegister(),
                            Thread.MAX_PRIORITY))))
            .connectTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
            .build());
  }

  @VisibleForTesting
  ClientSideSlb(
      ClientSideSlbConfig config, ScheduledExecutorService executor, OkHttpClient pingClient) {
    this.clock = config.getClock();
    this.pingEndpoint = Objects.requireNonNull(config.getPingEndpoint());
    this.serverPool = Objects.requireNonNull(config.getServerPool());
    this.eventBus = Objects.requireNonNull(config.getEventBus());
    Preconditions.checkArgument(serverPool.size() > 0, "No server URLs passed.");

    this.healthManager =
        new ServerHealthManager(
            config.getServerPoolName(),
            this.serverPool,
            config.getErrorCheckTimeRangeMillis(),
            config.getMaxErrorPercentage(),
            config.getLatencyCheckTimeRangeMillis(),
            config.getMaxAcceptableLatencyMillis(),
            config.getMinSamplesToReportError(),
            config.getEventBus(),
            this.clock);
    this.pingClient = pingClient;
    this.schedulerService = executor;
    backgroundHealthChecker =
        this.schedulerService.scheduleWithFixedDelay(
            this::backgroundThreadCallForHealthCheck,
            0,
            config.getHealthCheckIntervalMillis(),
            TimeUnit.MILLISECONDS);
  }

  @Override
  public URI getBestServer() throws NoHealthyServersException {
    return healthManager.getBestServer();
  }

  @Override
  public void reportRequestSuccess(URI server) {
    healthManager.reportRequestSuccess(server);
  }

  @Override
  public void reportRequestException(URI server) {
    healthManager.reportRequestError(server);
  }

  @Override
  public void close() {
    backgroundHealthChecker.cancel(true);
    schedulerService.shutdownNow();
    pingClient.dispatcher().executorService().shutdownNow();
  }

  // TODO(ruibm): Register for BuildStart events in the EventBus and force a health check then.
  // TODO(ruibm): Log into timeseries information about each run.
  // TODO(ruibm): Add cache health information to the SuperConsole.
  private void backgroundThreadCallForHealthCheck() {

    LOG.verbose("Starting pings. %s", toString());

    List<ListenableFuture<PerServerPingData>> futures = new ArrayList<>();
    for (URI serverUri : serverPool) {
      ServerPing serverPing = new ServerPing(serverUri);
      futures.add(serverPing.getFuture());
    }

    // Wait for all executions to complete or fail.
    try {
      List<PerServerPingData> allServerData = Futures.allAsList(futures).get();
      LoadBalancerPingEventData.Builder eventData = LoadBalancerPingEventData.builder();
      eventData.addAllPerServerData(allServerData);
      eventBus.post(new LoadBalancerPingEvent(eventData.build()));
      LOG.verbose("all pings complete %s", toString());
    } catch (InterruptedException ex) {
      LOG.info(ex, "ClientSideSlb was interrupted");
      Thread.currentThread().interrupt();
      throw new RuntimeException(ex);
    } catch (ExecutionException ex) {
      LOG.warn(ex, "Some pings failed");
    }
  }

  public class ServerPing implements Callback {

    private final SettableFuture<PerServerPingData> future = SettableFuture.create();
    URI serverUri;

    ServerPing(URI serverUri) {
      this.serverUri = serverUri;
      Request request =
          new Request.Builder().url(serverUri.resolve(pingEndpoint).toString()).get().build();
      pingClient.newCall(request).enqueue(this);
    }

    public ListenableFuture<PerServerPingData> getFuture() {
      return future;
    }

    /*
     Process the success result of the ping
    */
    @Override
    public void onResponse(Call call, Response response) throws IOException {
      PerServerPingData.Builder perServerData = PerServerPingData.builder().setServer(serverUri);

      long sentRequestMillis = response.sentRequestAtMillis();
      if (response.isSuccessful()) {
        try (ResponseBody responseBody = response.body()) {
          String body = responseBody.string();
          LOG.verbose("Sent ping to %s. Response: %s", serverUri.toString(), body);
        }
        long requestLatencyMillis = response.receivedResponseAtMillis() - sentRequestMillis;
        perServerData.setPingRequestLatencyMillis(requestLatencyMillis);
        healthManager.reportPingLatency(serverUri, requestLatencyMillis);
        healthManager.reportRequestSuccess(serverUri);
      } else {
        healthManager.reportRequestError(serverUri);
      }
      future.set(perServerData.build());
    }

    /*
     Process the failure result of the ping
    */
    @Override
    public void onFailure(Call call, IOException e) {
      healthManager.reportRequestError(serverUri);
      PerServerPingData.Builder perServerData = PerServerPingData.builder().setServer(serverUri);
      perServerData.setException(e);
      future.set(perServerData.build());
    }
  }
}
