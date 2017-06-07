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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.net.URI;
import java.util.Optional;

public class SlbBuckConfig {

  // SLB BuckConfig keys.
  private static final String SERVER_POOL = "slb_server_pool";
  private static final String PING_ENDPOINT = "slb_ping_endpoint";
  private static final String HEALTH_CHECK_INTERVAL_MILLIS = "slb_health_check_internal_millis";
  private static final String TIMEOUT_MILLIS = "slb_timeout_millis";
  private static final String ERROR_CHECK_TIME_RANGE_MILLIS = "slb_error_check_time_range_millis";
  private static final String MAX_ERROR_PERCENTAGE = "slb_max_error_percentage";
  private static final String LATENCY_CHECK_TIME_RANGE_MILLIS =
      "slb_latency_check_time_range_millis";
  private static final String MAX_ACCEPTABLE_LATENCY_MILLIS = "slb_max_acceptable_latency_millis";

  private final String parentSection;
  private final BuckConfig buckConfig;

  public SlbBuckConfig(BuckConfig config, String parentSection) {
    this.buckConfig = config;
    this.parentSection = parentSection;
  }

  private ImmutableList<URI> getServerPool() {
    ImmutableList<String> serverPool =
        buckConfig.getListWithoutComments(parentSection, SERVER_POOL);
    ImmutableList.Builder<URI> builder = ImmutableList.builder();
    for (String server : serverPool) {
      URI uri = URI.create(server);
      Preconditions.checkState(
          !Strings.isNullOrEmpty(uri.getScheme()),
          "A scheme must be provided for server [%s] in config [%s::%s].",
          server,
          parentSection,
          SERVER_POOL);
      builder.add(uri);
    }

    return builder.build();
  }

  public ClientSideSlb createClientSideSlb(Clock clock, BuckEventBus eventBus) {
    return new ClientSideSlb(createConfig(clock, eventBus));
  }

  public Optional<ClientSideSlb> tryCreatingClientSideSlb(Clock clock, BuckEventBus eventBus) {
    ClientSideSlbConfig config = createConfig(clock, eventBus);
    return ClientSideSlb.isSafeToCreate(config)
        ? Optional.of(new ClientSideSlb(config))
        : Optional.empty();
  }

  private ClientSideSlbConfig createConfig(Clock clock, BuckEventBus eventBus) {
    ClientSideSlbConfig.Builder configBuilder =
        ClientSideSlbConfig.builder()
            .setClock(clock)
            .setServerPool(getServerPool())
            .setEventBus(eventBus);

    if (buckConfig.getValue(parentSection, PING_ENDPOINT).isPresent()) {
      configBuilder.setPingEndpoint(buckConfig.getValue(parentSection, PING_ENDPOINT).get());
    }

    if (buckConfig.getValue(parentSection, TIMEOUT_MILLIS).isPresent()) {
      configBuilder.setConnectionTimeoutMillis(
          buckConfig.getLong(parentSection, TIMEOUT_MILLIS).get().intValue());
    }

    if (buckConfig.getValue(parentSection, HEALTH_CHECK_INTERVAL_MILLIS).isPresent()) {
      configBuilder.setHealthCheckIntervalMillis(
          buckConfig.getLong(parentSection, HEALTH_CHECK_INTERVAL_MILLIS).get().intValue());
    }

    if (buckConfig.getValue(parentSection, ERROR_CHECK_TIME_RANGE_MILLIS).isPresent()) {
      configBuilder.setErrorCheckTimeRangeMillis(
          buckConfig.getLong(parentSection, ERROR_CHECK_TIME_RANGE_MILLIS).get().intValue());
    }

    if (buckConfig.getValue(parentSection, MAX_ACCEPTABLE_LATENCY_MILLIS).isPresent()) {
      configBuilder.setMaxAcceptableLatencyMillis(
          buckConfig.getLong(parentSection, MAX_ACCEPTABLE_LATENCY_MILLIS).get().intValue());
    }

    if (buckConfig.getValue(parentSection, LATENCY_CHECK_TIME_RANGE_MILLIS).isPresent()) {
      configBuilder.setLatencyCheckTimeRangeMillis(
          buckConfig.getLong(parentSection, LATENCY_CHECK_TIME_RANGE_MILLIS).get().intValue());
    }

    if (buckConfig.getValue(parentSection, MAX_ERROR_PERCENTAGE).isPresent()) {
      configBuilder.setMaxErrorPercentage(
          buckConfig.getFloat(parentSection, MAX_ERROR_PERCENTAGE).get());
    }

    return configBuilder.build();
  }
}
