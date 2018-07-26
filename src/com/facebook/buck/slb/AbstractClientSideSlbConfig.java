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

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableList;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractClientSideSlbConfig {

  // Defaults
  public static final String PING_ENDPOINT = "/status.php";

  public static final int HEALTH_CHECK_INTERVAL_MILLIS = (int) TimeUnit.SECONDS.toMillis(25);
  public static final int CONNECTION_TIMEOUT_MILLIS = (int) TimeUnit.SECONDS.toMillis(1);

  public static final int ERROR_CHECK_TIME_RANGE_MILLIS = (int) TimeUnit.MINUTES.toMillis(5);
  public static final float MAX_ERROR_PERCENTAGE = 0.1f;

  public static final int LATENCY_CHECK_TIME_RANGE_MILLIS = ERROR_CHECK_TIME_RANGE_MILLIS;
  public static final int MAX_ACCEPTABLE_LATENCY_MILLIS = (int) TimeUnit.SECONDS.toMillis(1);

  public static final int MIN_SAMPLES_TO_REPORT_ERROR_DEFAULT_VALUE = 1;

  public abstract Clock getClock();

  public abstract ImmutableList<URI> getServerPool();

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
  public float getMaxErrorPercentage() {
    return MAX_ERROR_PERCENTAGE;
  }

  @Value.Default
  public int getMinSamplesToReportError() {
    return MIN_SAMPLES_TO_REPORT_ERROR_DEFAULT_VALUE;
  }
}
