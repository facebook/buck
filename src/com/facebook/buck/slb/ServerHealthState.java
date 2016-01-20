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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.net.URI;
import java.util.List;
import java.util.ListIterator;

public class ServerHealthState {
  private static final int MAX_STORED_SAMPLES = 100;

  private final int maxSamplesStored;
  private final URI server;
  private final List<LatencySample> latencies;
  private final List<Long> errors;

  public ServerHealthState(URI server) {
    this(server, MAX_STORED_SAMPLES);
  }

  public ServerHealthState(URI server, int maxSamplesStored) {
    Preconditions.checkArgument(
        maxSamplesStored > 0,
        "The maximum number of samples stored must be positive instead of [%d].",
        maxSamplesStored);
    this.maxSamplesStored = maxSamplesStored;
    this.server = server;
    this.latencies = Lists.newLinkedList();
    this.errors = Lists.newLinkedList();
  }

  /**
   * NOTE: Assumes nowMillis is roughly non-decreasing in consecutive calls.
   * @param nowMillis
   * @param latencyMillis
   */
  public void reportLatency(long nowMillis, long latencyMillis) {
    synchronized (latencies) {
      latencies.add(new LatencySample(nowMillis, latencyMillis));
      keepWithinSizeLimit(latencies);
    }
  }

  /**
   * NOTE: Assumes nowMillis is roughly non-decreasing in consecutive calls.
   * @param nowMillis
   */
  public void reportError(long nowMillis) {
    synchronized (errors) {
      errors.add(nowMillis);
      keepWithinSizeLimit(errors);
    }
  }

  public int getLatencySampleCount() {
    synchronized (latencies) {
      return latencies.size();
    }
  }

  public int getErrorSampleCount() {
    synchronized (errors) {
      return errors.size();
    }
  }

  private <T> void keepWithinSizeLimit(List<T> list) {
    while (list.size() > maxSamplesStored) {
      // Assume list is time sorted; always remove oldest sample.
      list.remove(0);
    }
  }

  public float getErrorsPerSecond(long nowMillis, int timeRangeMillis) {
    float count = 0;
    long initialMillis = nowMillis - timeRangeMillis;
    synchronized (errors) {
      ListIterator<Long> iterator = errors.listIterator(errors.size());
      while (iterator.hasPrevious()) {
        long errorMillis = iterator.previous();
        if (errorMillis >= initialMillis && errorMillis <= nowMillis) {
          ++count;
        }
      }
    }

    return count / timeRangeMillis;
  }

  public URI getServer() {
    return server;
  }

  public long getLatencyMillis(long nowMillis, int timeRangeMillis) {
    int count = 0;
    int sum = 0;
    long initialMillis = nowMillis - timeRangeMillis;
    synchronized (latencies) {
      ListIterator<LatencySample> iterator = latencies.listIterator(latencies.size());
      while (iterator.hasPrevious()) {
        LatencySample sample = iterator.previous();
        if (sample.getEpochMillis() >= initialMillis &&
            sample.getEpochMillis() <= nowMillis) {
          sum += sample.getLatencyMillis();
          ++count;
        }
      }
    }

    if (count > 0) {
      return sum / count;
    } else {
      return -1;
    }
  }

  public String toString(long nowMillis, int timeRangeMillis) {
    return "ServerHealthState{" +
        "server=" + server +
        ", latencyMillis=" + getLatencyMillis(nowMillis, timeRangeMillis) +
        ", errorCount=" + getErrorsPerSecond(nowMillis, timeRangeMillis) +
        '}';
  }

  private static final class LatencySample {
    private final long latencyMillis;
    private final long epochMillis;

    public LatencySample(long epochMillis, long latencyMillis) {
      this.latencyMillis = latencyMillis;
      this.epochMillis = epochMillis;
    }

    public long getLatencyMillis() {
      return latencyMillis;
    }

    public long getEpochMillis() {
      return epochMillis;
    }
  }
}
