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

import org.junit.Assert;
import org.junit.Test;

import java.net.URI;

public class ServerHealthStateTest {
  private static final URI SERVER = URI.create("http://localhost:4242");
  private static final long NOW_MILLIS = 0;
  private static final int RANGE_MILLIS = 42;
  private static final float DELTA = 0.001f;

  @Test
  public void testLatencyAndErrorsWithoutSamples() {
    ServerHealthState state = new ServerHealthState(SERVER);
    Assert.assertEquals(-1, state.getLatencyMillis(NOW_MILLIS, RANGE_MILLIS));
    Assert.assertEquals(0, state.getErrorsPerSecond(NOW_MILLIS, RANGE_MILLIS), DELTA);
  }

  @Test
  public void testLatencyAndErrorsWithoutSamplesOutOfRange() {
    ServerHealthState state = new ServerHealthState(SERVER);
    reportSamples(state, NOW_MILLIS + 1, 21);
    reportSamples(state, NOW_MILLIS - RANGE_MILLIS - 1, 21);
    Assert.assertEquals(-1, state.getLatencyMillis(NOW_MILLIS, RANGE_MILLIS));
    Assert.assertEquals(0, state.getErrorsPerSecond(NOW_MILLIS, RANGE_MILLIS), DELTA);
  }

  @Test
  public void testLatencyAndErrorsOneSample() {
    ServerHealthState state = new ServerHealthState(SERVER);
    reportSamples(state, NOW_MILLIS, 21);
    Assert.assertEquals(21, state.getLatencyMillis(NOW_MILLIS, RANGE_MILLIS));
    Assert.assertEquals(
        1.0f / RANGE_MILLIS,
        state.getErrorsPerSecond(NOW_MILLIS, RANGE_MILLIS), DELTA);
  }

  @Test
  public void testAgainstMemoryLeak() {
    int maxSamples = 42;
    ServerHealthState state = new ServerHealthState(SERVER, maxSamples);
    for (int i = 0; i < maxSamples * 2; ++i) {
      reportSamples(state, NOW_MILLIS, 1);
    }

    Assert.assertEquals(maxSamples, state.getErrorSampleCount());
    Assert.assertEquals(maxSamples, state.getLatencySampleCount());
  }

  private void reportSamples(ServerHealthState state, long epochMillis, int latencyMillis) {
    state.reportError(epochMillis);
    state.reportLatency(epochMillis, latencyMillis);
  }
}
