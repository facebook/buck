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
import com.facebook.buck.model.Pair;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.net.URI;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServerHealthManager {

  private static final Comparator<Pair<URI, Long>> LATENCY_COMPARATOR =
      (o1, o2) -> (int) (o1.getSecond() - o2.getSecond());

  // TODO(ruibm): It could be useful to preserve this state across runs in the local fs.
  private final ConcurrentHashMap<URI, ServerHealthState> servers;
  private final int maxAcceptableLatencyMillis;
  private final int latencyCheckTimeRangeMillis;
  private final float maxErrorPercentage;
  private final int errorCheckTimeRangeMillis;
  private final BuckEventBus eventBus;

  public ServerHealthManager(
      ImmutableList<URI> servers,
      int errorCheckTimeRangeMillis,
      float maxErrorPercentage,
      int latencyCheckTimeRangeMillis,
      int maxAcceptableLatencyMillis,
      BuckEventBus eventBus) {
    this.errorCheckTimeRangeMillis = errorCheckTimeRangeMillis;
    this.maxErrorPercentage = maxErrorPercentage;
    this.latencyCheckTimeRangeMillis = latencyCheckTimeRangeMillis;
    this.maxAcceptableLatencyMillis = maxAcceptableLatencyMillis;
    this.servers = new ConcurrentHashMap<>();
    for (URI server : servers) {
      this.servers.put(server, new ServerHealthState(server));
    }
    this.eventBus = eventBus;
  }

  public void reportPingLatency(URI server, long epochMillis, long latencyMillis) {
    Preconditions.checkState(servers.containsKey(server), "Unknown server [%s]", server);
    servers.get(server).reportPingLatency(epochMillis, latencyMillis);
  }

  public void reportRequestError(URI server, long epochMillis) {
    Preconditions.checkState(servers.containsKey(server), "Unknown server [%s]", server);
    servers.get(server).reportRequestError(epochMillis);
  }

  public void reportRequestSuccess(URI server, long epochMillis) {
    Preconditions.checkState(servers.containsKey(server), "Unknown server [%s]", server);
    servers.get(server).reportRequestSuccess(epochMillis);
  }

  public URI getBestServer(long epochMillis) throws NoHealthyServersException {
    ServerHealthManagerEventData.Builder data = ServerHealthManagerEventData.builder();
    Map<URI, PerServerData.Builder> allPerServerData = Maps.newHashMap();
    try {
      // TODO(ruibm): Computations in this method could be cached and only refreshed
      // every 10 seconds to avoid call bursts causing unnecessary CPU consumption.

      List<Pair<URI, Long>> serverLatencies = Lists.newArrayList();
      for (ServerHealthState state : servers.values()) {
        URI server = state.getServer();
        PerServerData.Builder perServerData = PerServerData.builder().setServer(server);
        allPerServerData.put(server, perServerData);

        float errorPercentage = state.getErrorPercentage(epochMillis, errorCheckTimeRangeMillis);
        long latencyMillis = state.getPingLatencyMillis(epochMillis, latencyCheckTimeRangeMillis);
        if (errorPercentage <= maxErrorPercentage && latencyMillis <= maxAcceptableLatencyMillis) {
          serverLatencies.add(new Pair<>(state.getServer(), latencyMillis));
        } else {
          perServerData.setServerUnhealthy(true);
        }
      }

      if (serverLatencies.size() == 0) {
        data.setNoHealthyServersAvailable(true);
        throw new NoHealthyServersException(String.format(
            "No servers available. Too many errors reported by all servers in the pool: [%s]",
            Joiner.on(", ").join(FluentIterable.from(servers.keySet()).transform(
                Object::toString))));
      }

      Collections.sort(serverLatencies, LATENCY_COMPARATOR);
      URI bestServer = serverLatencies.get(0).getFirst();
      Preconditions.checkNotNull(allPerServerData.get(bestServer)).setBestServer(true);
      return bestServer;
    } finally {
      for (PerServerData.Builder builder : allPerServerData.values()) {
        data.addPerServerData(builder.build());
      }
      eventBus.post(new ServerHealthManagerEvent(data.build()));
    }
  }

  public String toString(long epochMillis) {
    StringBuilder builder = new StringBuilder("ServerHealthManager{\n");
    for (ServerHealthState server : servers.values()) {
      builder.append(String.format(
          "  %s\n",
          server.toString(epochMillis, latencyCheckTimeRangeMillis)));
    }

    builder.append("}");
    return builder.toString();
  }
}
