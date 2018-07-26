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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.net.URI;
import java.util.Optional;
import okhttp3.Connection;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class SlbBuckConfig {

  private static final Logger LOG = Logger.get(SlbBuckConfig.class);

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
  private static final String MIN_SAMPLES_TO_REPORT_ERROR = "slb_min_samples_to_report_error";

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
    return new ClientSideSlb(createConfig(clock, eventBus), createOkHttpClientBuilder());
  }

  public Optional<ClientSideSlb> tryCreatingClientSideSlb(Clock clock, BuckEventBus eventBus) {
    ClientSideSlbConfig config = createConfig(clock, eventBus);
    return ClientSideSlb.isSafeToCreate(config)
        ? Optional.of(new ClientSideSlb(config, createOkHttpClientBuilder()))
        : Optional.empty();
  }

  private OkHttpClient.Builder createOkHttpClientBuilder() {
    OkHttpClient.Builder clientBuilder = new OkHttpClient().newBuilder();
    clientBuilder
        .networkInterceptors()
        .add(
            chain -> {
              String remoteAddress = null;
              Connection connection = chain.connection();
              if (connection != null) {
                remoteAddress = connection.socket().getRemoteSocketAddress().toString();
              } else {
                LOG.warn(String.format("No available connection."));
              }
              Response response = chain.proceed(chain.request());
              if (response.code() != 200 && remoteAddress != null) {
                LOG.warn(
                    String.format(
                        "Connection to %s failed with code %d", remoteAddress, response.code()));
              }
              return response;
            });
    return clientBuilder;
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

    if (buckConfig.getValue(parentSection, MIN_SAMPLES_TO_REPORT_ERROR).isPresent()) {
      configBuilder.setMinSamplesToReportError(
          buckConfig.getInteger(parentSection, MIN_SAMPLES_TO_REPORT_ERROR).getAsInt());
    }
    return configBuilder.build();
  }
}
