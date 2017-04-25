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

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.event.NetworkEvent.BytesReceivedEvent;
import com.facebook.buck.model.Pair;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class NetworkStatsKeeper {
  private static final Duration DOWNLOAD_SPEED_CALCULATION_INTERVAL = Duration.ofMillis(1000);

  private final AtomicLong bytesDownloaded;
  private final AtomicLong bytesDownloadedInLastInterval;
  private final AtomicLong artifactDownloaded;
  private long artifactDownloadInProgressCount;
  private double downloadSpeedForLastInterval;
  private long firstDownloadStartTimestamp;
  private long lastDownloadFinishedTimeMs;
  private long totalDownloadTimeMillis;
  private long currentIntervalDownloadTimeMillis;
  private final ScheduledExecutorService scheduler;
  private Clock clock;

  public NetworkStatsKeeper() {
    this.bytesDownloaded = new AtomicLong(0);
    this.bytesDownloadedInLastInterval = new AtomicLong(0);
    this.artifactDownloaded = new AtomicLong(0);
    this.artifactDownloadInProgressCount = 0;
    this.downloadSpeedForLastInterval = 0;
    this.firstDownloadStartTimestamp = 0;
    this.lastDownloadFinishedTimeMs = 0;
    this.totalDownloadTimeMillis = 0;
    this.currentIntervalDownloadTimeMillis = 0;
    this.clock = new DefaultClock();
    this.scheduler =
        Executors.newScheduledThreadPool(
            1,
            new ThreadFactoryBuilder().setNameFormat(getClass().getSimpleName() + "-%d").build());
    scheduleDownloadSpeedCalculation();
  }

  private void scheduleDownloadSpeedCalculation() {
    long calculationInterval = DOWNLOAD_SPEED_CALCULATION_INTERVAL.toMillis();
    TimeUnit timeUnit = TimeUnit.MILLISECONDS;

    @SuppressWarnings("unused")
    ScheduledFuture<?> unused =
        scheduler.scheduleAtFixedRate(
            this::calculateDownloadSpeedInLastInterval,
            /* initialDelay */ calculationInterval,
            /* period */ calculationInterval,
            timeUnit);
  }

  @VisibleForTesting
  void calculateDownloadSpeedInLastInterval() {
    synchronized (this) {
      long timeSpentDownloadingInThisInterval = getDownloadTimeForThisInterval();
      downloadSpeedForLastInterval = calculateDownloadSpeed(timeSpentDownloadingInThisInterval);
      totalDownloadTimeMillis += timeSpentDownloadingInThisInterval;
      currentIntervalDownloadTimeMillis = 0;
    }
  }

  private double calculateDownloadSpeed(long timeSpentDownloadingInThisInterval) {
    if (timeSpentDownloadingInThisInterval <= 0) {
      return 0.0;
    }
    return ((double) 1000 * bytesDownloadedInLastInterval.getAndSet(0))
        / timeSpentDownloadingInThisInterval;
  }

  private long getDownloadTimeForThisInterval() {
    long timeSpentDownloadingInThisInterval = currentIntervalDownloadTimeMillis;
    //Downloads may be interleaved.
    if (artifactDownloadInProgressCount != 0) {
      long currentTime = clock.currentTimeMillis();
      timeSpentDownloadingInThisInterval += (currentTime - firstDownloadStartTimestamp);
      firstDownloadStartTimestamp = currentTime;
    }
    return timeSpentDownloadingInThisInterval;
  }

  public void bytesReceived(BytesReceivedEvent bytesReceivedEvent) {
    bytesDownloaded.getAndAdd(bytesReceivedEvent.getBytesReceived());
    bytesDownloadedInLastInterval.getAndAdd(bytesReceivedEvent.getBytesReceived());
  }

  public Pair<Long, SizeUnit> getBytesDownloaded() {
    return new Pair<>(bytesDownloaded.get(), SizeUnit.BYTES);
  }

  public Pair<Double, SizeUnit> getDownloadSpeed() {
    return new Pair<>(downloadSpeedForLastInterval, SizeUnit.BYTES);
  }

  public Pair<Double, SizeUnit> getAverageDownloadSpeed() {
    if (totalDownloadTimeMillis <= 0) {
      return new Pair<>(0.0, SizeUnit.BYTES);
    }
    double avgSpeed = ((double) 1000 * bytesDownloaded.get()) / totalDownloadTimeMillis;
    return new Pair<>(avgSpeed, SizeUnit.BYTES);
  }

  public void stopScheduler() {
    scheduler.shutdownNow();
  }

  public long getDownloadedArtifactDownloaded() {
    return artifactDownloaded.get();
  }

  public void artifactDownloadFinished(HttpArtifactCacheEvent.Finished event) {
    artifactDownloaded.incrementAndGet();
    synchronized (this) {
      --artifactDownloadInProgressCount;
      if (event.getTimestamp() > lastDownloadFinishedTimeMs) {
        lastDownloadFinishedTimeMs = event.getTimestamp();
      }
      // this is for calculating avg download speed accurately. Think the case where
      // download is interleaved.
      if (artifactDownloadInProgressCount == 0) {
        currentIntervalDownloadTimeMillis +=
            (lastDownloadFinishedTimeMs - firstDownloadStartTimestamp);
        firstDownloadStartTimestamp = 0;
      }
    }
  }

  public void artifactDownloadedStarted(HttpArtifactCacheEvent.Started event) {
    synchronized (this) {
      ++artifactDownloadInProgressCount;
      if (firstDownloadStartTimestamp == 0 || event.getTimestamp() < firstDownloadStartTimestamp) {
        firstDownloadStartTimestamp = event.getTimestamp();
      }
    }
  }

  //only for testing
  protected void setClock(Clock clock) {
    this.clock = clock;
  }
}
