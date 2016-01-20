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
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.squareup.okhttp.OkHttpClient;

import org.immutables.value.Value;

import java.net.URI;
import java.util.concurrent.ScheduledExecutorService;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractClientSideSlbConfig {

  // Defaults
  public static final String PING_ENDPOINT = "/status.php";
  public static final int HEALTH_CHECK_INTERVAL_MILLIS = 60 * 1000;
  public static final int CONNECTION_TIMEOUT_MILLIS = 2 * 1000;
  public static final int ERROR_CHECK_TIME_RANGE_MILLIS = 10 * 60 * 60 * 1000;
  public static final float MAX_ERRORS_PER_SECOND = 1;
  public static final int LATENCY_CHECK_TIME_RANGE_MILLIS =
      ERROR_CHECK_TIME_RANGE_MILLIS;
  public static final int MAX_ACCEPTABLE_LATENCY_MILLIS = 1000;

  public abstract Clock getClock();
  public abstract ScheduledExecutorService getSchedulerService();
  public abstract ImmutableList<URI> getServerPool();
  public abstract OkHttpClient getPingHttpClient();
  public abstract BuckEventBus getEventBus();

  @Value.Default
  public int getErrorCheckTimeRangeMillis() {
    return ERROR_CHECK_TIME_RANGE_MILLIS;
  }

  @Value.Default
  public int getLatencyCheckTimeRangeMillis() {
    return LATENCY_CHECK_TIME_RANGE_MILLIS;
  }

  @Value.Default
  public int getMaxAcceptableLatencyMillis() {
    return MAX_ACCEPTABLE_LATENCY_MILLIS;
  }

  @Value.Default
  public int getConnectionTimeoutMillis() {
    return CONNECTION_TIMEOUT_MILLIS;
  }

  @Value.Default
  public int getHealthCheckIntervalMillis() {
    return HEALTH_CHECK_INTERVAL_MILLIS;
  }

  @Value.Default
  public String getPingEndpoint() {
    return PING_ENDPOINT;
  }

  @Value.Default
  public float getMaxErrorsPerSecond() {
    return MAX_ERRORS_PER_SECOND;
  }
}
