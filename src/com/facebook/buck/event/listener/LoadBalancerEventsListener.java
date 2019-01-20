/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.event.listener;

import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.IntegerCounter;
import com.facebook.buck.counters.SamplingCounter;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.slb.LoadBalancedServiceEvent;
import com.facebook.buck.slb.LoadBalancedServiceEventData;
import com.facebook.buck.slb.LoadBalancerPingEvent;
import com.facebook.buck.slb.LoadBalancerPingEventData;
import com.facebook.buck.slb.PerServerData;
import com.facebook.buck.slb.PerServerPingData;
import com.facebook.buck.slb.ServerHealthManagerEvent;
import com.facebook.buck.slb.ServerHealthManagerEventData;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

public class LoadBalancerEventsListener implements BuckEventListener {
  public static final String COUNTER_CATEGORY = "buck_slb_counters";
  private static final String POOL_NAME_TAG = "server_pool_name";

  private final CounterRegistry registry;
  private final ConcurrentMap<URI, ServerCounters> allServerCounters;
  private final ConcurrentMap<String, IntegerCounter> noHealthyServersCounters;

  public LoadBalancerEventsListener(CounterRegistry registry) {
    this.registry = registry;
    this.allServerCounters = Maps.newConcurrentMap();
    this.noHealthyServersCounters = Maps.newConcurrentMap();
  }

  @Subscribe
  public void onLoadBalancerPingEvent(LoadBalancerPingEvent event) {
    LoadBalancerPingEventData data = event.getData();
    for (PerServerPingData perServerData : data.getPerServerData()) {
      ServerCounters counters = getServerCounters(perServerData.getServer());
      counters.getPingRequestCount().inc();
      if (perServerData.getException().isPresent()) {
        Exception exception = perServerData.getException().get();
        if (exception instanceof SocketTimeoutException) {
          counters.getPingRequestTimeoutCount().inc();
        } else {
          counters.getPingRequestErrorCount().inc();
        }
      }

      if (perServerData.getPingRequestLatencyMillis().isPresent()) {
        counters
            .getPingRequestLatencyMillis()
            .addSample(perServerData.getPingRequestLatencyMillis().get());
      }
    }
  }

  @Subscribe
  public void onServerHealthManagerEvent(ServerHealthManagerEvent event) {
    ServerHealthManagerEventData data = event.getData();
    IntegerCounter noHealthyServersCounter = getNoHealthyServerCounter(data.getServerPoolName());
    if (data.noHealthyServersAvailable()) {
      noHealthyServersCounter.inc();
    }

    for (PerServerData perServerData : data.getPerServerData()) {
      ServerCounters counters = getServerCounters(perServerData.getServer());
      if (perServerData.isServerUnhealthy()) {
        counters.getServerNotHealthyCount().inc();
      }
      if (perServerData.isBestServer()) {
        counters.getIsBestServerCount().inc();
      }
    }
  }

  @Subscribe
  public void onLoadBalancedServiceEvent(LoadBalancedServiceEvent event) {
    LoadBalancedServiceEventData data = event.getData();
    ServerCounters counters = getServerCounters(data.getServer());
    counters.getRequestCount().inc();
    if (data.getRequestSizeBytes().isPresent()) {
      counters.getRequestSizeBytes().addSample(data.getRequestSizeBytes().get());
    }
    if (data.getLatencyMicros().isPresent()) {
      counters.getLatencyMicros().addSample(data.getLatencyMicros().get());
    }
    if (data.getResponseSizeBytes().isPresent()) {
      counters.getResponseSizeBytes().addSample(data.getResponseSizeBytes().get());
    }
    if (data.getException().isPresent()) {
      Exception exception = data.getException().get();
      if (exception instanceof SocketTimeoutException) {
        counters.getRequestTimeoutCount().inc();
      } else {
        counters.getRequestErrorCount().inc();
      }
    }
  }

  private ServerCounters getServerCounters(URI server) {
    synchronized (allServerCounters) {
      if (!allServerCounters.containsKey(server)) {
        allServerCounters.put(server, new ServerCounters(registry, server));
      }
      return Objects.requireNonNull(allServerCounters.get(server));
    }
  }

  private IntegerCounter getNoHealthyServerCounter(String poolName) {
    synchronized (allServerCounters) {
      if (!noHealthyServersCounters.containsKey(poolName)) {
        IntegerCounter counter =
            registry.newIntegerCounter(
                COUNTER_CATEGORY,
                "no_healthy_servers_count",
                ImmutableMap.of(POOL_NAME_TAG, poolName));
        noHealthyServersCounters.put(poolName, counter);
      }
      return Objects.requireNonNull(noHealthyServersCounters.get(poolName));
    }
  }

  public static class ServerCounters {
    public static final String PER_SERVER_CATEGORY = "buck_slb_counters_per_server";
    public static final String SERVER_TAG = "server";

    private final SamplingCounter pingRequestLatencyMillis;
    private final IntegerCounter pingRequestCount;
    private final IntegerCounter pingRequestErrorCount;
    private final IntegerCounter pingRequestTimeoutCount;

    private final IntegerCounter serverNotHealthyCount;
    private final IntegerCounter isBestServerCount;

    private final SamplingCounter requestSizeBytes;
    private final SamplingCounter latencyMicros;
    private final SamplingCounter responseSizeBytes;
    private final IntegerCounter requestCount;
    private final IntegerCounter requestErrorCount;
    private final IntegerCounter requestTimeoutCount;

    public ServerCounters(CounterRegistry registry, URI server) {
      this.pingRequestLatencyMillis =
          registry.newSamplingCounter(
              PER_SERVER_CATEGORY, "ping_request_latency_millis", getTagsForServer(server));
      this.pingRequestCount =
          registry.newIntegerCounter(
              PER_SERVER_CATEGORY, "ping_request_count", getTagsForServer(server));
      this.pingRequestErrorCount =
          registry.newIntegerCounter(
              PER_SERVER_CATEGORY, "ping_request_error_count", getTagsForServer(server));
      this.pingRequestTimeoutCount =
          registry.newIntegerCounter(
              PER_SERVER_CATEGORY, "ping_request_timeout_count", getTagsForServer(server));
      this.serverNotHealthyCount =
          registry.newIntegerCounter(
              PER_SERVER_CATEGORY, "server_not_healthy_count", getTagsForServer(server));
      this.isBestServerCount =
          registry.newIntegerCounter(
              PER_SERVER_CATEGORY, "is_best_server_count", getTagsForServer(server));

      this.requestSizeBytes =
          registry.newSamplingCounter(
              PER_SERVER_CATEGORY, "request_size_bytes", getTagsForServer(server));
      this.latencyMicros =
          registry.newSamplingCounter(PER_SERVER_CATEGORY, "latency_us", getTagsForServer(server));
      this.responseSizeBytes =
          registry.newSamplingCounter(
              PER_SERVER_CATEGORY, "response_size_bytes", getTagsForServer(server));
      this.requestCount =
          registry.newIntegerCounter(
              PER_SERVER_CATEGORY, "request_count", getTagsForServer(server));
      this.requestErrorCount =
          registry.newIntegerCounter(
              PER_SERVER_CATEGORY, "request_error_count", getTagsForServer(server));
      this.requestTimeoutCount =
          registry.newIntegerCounter(
              PER_SERVER_CATEGORY, "request_timeout_count", getTagsForServer(server));
    }

    public IntegerCounter getIsBestServerCount() {
      return isBestServerCount;
    }

    public SamplingCounter getPingRequestLatencyMillis() {
      return pingRequestLatencyMillis;
    }

    public IntegerCounter getPingRequestCount() {
      return pingRequestCount;
    }

    public IntegerCounter getPingRequestErrorCount() {
      return pingRequestErrorCount;
    }

    public IntegerCounter getPingRequestTimeoutCount() {
      return pingRequestTimeoutCount;
    }

    public IntegerCounter getServerNotHealthyCount() {
      return serverNotHealthyCount;
    }

    public SamplingCounter getRequestSizeBytes() {
      return requestSizeBytes;
    }

    public SamplingCounter getLatencyMicros() {
      return latencyMicros;
    }

    public SamplingCounter getResponseSizeBytes() {
      return responseSizeBytes;
    }

    public IntegerCounter getRequestCount() {
      return requestCount;
    }

    public IntegerCounter getRequestErrorCount() {
      return requestErrorCount;
    }

    public IntegerCounter getRequestTimeoutCount() {
      return requestTimeoutCount;
    }

    public static ImmutableMap<String, String> getTagsForServer(URI server) {
      return ImmutableMap.of(SERVER_TAG, server.toString());
    }
  }
}
