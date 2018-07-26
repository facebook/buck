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

import static com.facebook.buck.slb.AbstractClientSideSlbConfig.MIN_SAMPLES_TO_REPORT_ERROR_DEFAULT_VALUE;

import com.google.common.base.Preconditions;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

public class ServerHealthState {
  private static final int MAX_STORED_SAMPLES = 100;

  private final int maxSamplesStored;
  // This value sets how many samples we must in the sliding window before we determine the server
  // is unhealthy. It is to avoid cases when one error is dims the server unhealthy.
  private final int minSamplesToReportError;
  private final URI server;
  private final List<LatencySample> pingLatencies;
  private final List<RequestSample> requests;
  private float lastReportedErrorPercentage;
  private int lastReportedSamples;
  private long lastReportedLatency;

  public ServerHealthState(URI server) {
    this(server, MAX_STORED_SAMPLES, MIN_SAMPLES_TO_REPORT_ERROR_DEFAULT_VALUE);
  }

  public ServerHealthState(URI server, int minSamplesToReportError) {
    this(server, MAX_STORED_SAMPLES, minSamplesToReportError);
  }

  public ServerHealthState(URI server, int maxSamplesStored, int minSamplesToReportError) {
    Preconditions.checkArgument(
        maxSamplesStored > 0,
        "The maximum number of samples stored must be positive instead of [%s].",
        maxSamplesStored);
    this.maxSamplesStored = maxSamplesStored;
    this.minSamplesToReportError = minSamplesToReportError;
    this.server = server;
    this.pingLatencies = new LinkedList<>();
    this.requests = new LinkedList<>();
    this.lastReportedLatency = 0;
    this.lastReportedErrorPercentage = 0;
  }

  /**
   * NOTE: Assumes nowMillis is roughly non-decreasing in consecutive calls.
   *
   * @param nowMillis
   * @param latencyMillis
   */
  public void reportPingLatency(long nowMillis, long latencyMillis) {
    synchronized (pingLatencies) {
      pingLatencies.add(new LatencySample(nowMillis, latencyMillis));
      keepWithinSizeLimit(pingLatencies);
    }
  }

  /**
   * NOTE: Assumes nowMillis is roughly non-decreasing in consecutive calls.
   *
   * @param nowMillis
   */
  public void reportRequestSuccess(long nowMillis) {
    reportRequest(nowMillis, true);
  }

  /**
   * NOTE: Assumes nowMillis is roughly non-decreasing in consecutive calls.
   *
   * @param nowMillis
   */
  public void reportRequestError(long nowMillis) {
    reportRequest(nowMillis, false);
  }

  private void reportRequest(long nowMillis, boolean wasSuccessful) {
    synchronized (requests) {
      requests.add(new RequestSample(nowMillis, wasSuccessful));
      keepWithinSizeLimit(requests);
    }
  }

  public int getPingLatencySampleCount() {
    synchronized (pingLatencies) {
      return pingLatencies.size();
    }
  }

  public int getRequestSampleCount() {
    synchronized (requests) {
      return requests.size();
    }
  }

  private <T> void keepWithinSizeLimit(List<T> list) {
    while (list.size() > maxSamplesStored) {
      // Assume list is time sorted; always remove oldest sample.
      list.remove(0);
    }
  }

  /**
   * @param nowMillis Current timestamp.
   * @param timeRangeMillis Time range for 'nowMillis' to compute the errorPercentage for.
   * @return Value in the interval [0.0, 1.0].
   */
  public float getErrorPercentage(long nowMillis, int timeRangeMillis) {
    int errorCount = 0;
    int requestCount = 0;
    long initialMillis = nowMillis - timeRangeMillis;
    synchronized (requests) {
      ListIterator<RequestSample> iterator = requests.listIterator(requests.size());
      while (iterator.hasPrevious()) {
        RequestSample sample = iterator.previous();
        long requestMillis = sample.getEpochMillis();
        if (requestMillis >= initialMillis && requestMillis <= nowMillis) {
          if (!sample.wasSuccessful()) {
            ++errorCount;
          }

          ++requestCount;
        }
      }
    }
    lastReportedSamples = requestCount;
    lastReportedErrorPercentage =
        (requestCount == 0 || requestCount < minSamplesToReportError)
            ? 0
            : (errorCount / ((float) requestCount));

    return lastReportedErrorPercentage;
  }

  public URI getServer() {
    return server;
  }

  public long getPingLatencyMillis(long nowMillis, int timeRangeMillis) {
    int count = 0;
    int sum = 0;
    long initialMillis = nowMillis - timeRangeMillis;
    synchronized (pingLatencies) {
      ListIterator<LatencySample> iterator = pingLatencies.listIterator(pingLatencies.size());
      while (iterator.hasPrevious()) {
        LatencySample sample = iterator.previous();
        if (sample.getEpochMillis() >= initialMillis && sample.getEpochMillis() <= nowMillis) {
          sum += sample.getLatencyMillis();
          ++count;
        }
      }
    }

    lastReportedLatency = (count > 0) ? sum / count : -1;
    return lastReportedLatency;
  }

  public String toString(long nowMillis, int timeRangeMillis) {
    return "ServerHealthState{"
        + "server="
        + server
        + ", latencyMillis="
        + getPingLatencyMillis(nowMillis, timeRangeMillis)
        + ", errorCount="
        + getErrorPercentage(nowMillis, timeRangeMillis)
        + '}';
  }

  public float getLastReportedErrorPercentage() {
    return lastReportedErrorPercentage;
  }

  public int getLastReportedSamples() {
    return lastReportedSamples;
  }

  public long getLastReportedLatency() {
    return lastReportedLatency;
  }

  private static final class RequestSample {
    private final long epochMillis;
    private final boolean wasSuccessful;

    private RequestSample(long epochMillis, boolean wasSuccessful) {
      this.epochMillis = epochMillis;
      this.wasSuccessful = wasSuccessful;
    }

    public long getEpochMillis() {
      return epochMillis;
    }

    public boolean wasSuccessful() {
      return wasSuccessful;
    }
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
