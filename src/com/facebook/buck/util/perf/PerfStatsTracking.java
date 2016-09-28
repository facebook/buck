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

package com.facebook.buck.util.perf;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.Logger;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.ServiceManager;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

/**
 * Periodically probes for process-wide perf-related metrics.
 */
public class PerfStatsTracking extends AbstractScheduledService implements AutoCloseable {
  private final BuckEventBus eventBus;
  private final ServiceManager serviceManager;
  private final InvocationInfo invocationInfo;

  public PerfStatsTracking(
      BuckEventBus eventBus,
      InvocationInfo invocationInfo) {
    this.eventBus = eventBus;
    this.serviceManager = new ServiceManager(ImmutableList.of(this));
    this.invocationInfo = invocationInfo;
    serviceManager.startAsync();
  }

  public void probeMemory() {
    long freeMemoryBytes = Runtime.getRuntime().freeMemory();
    long totalMemoryBytes = Runtime.getRuntime().totalMemory();

    long totalGcTimeMs = 0;
    for (GarbageCollectorMXBean gcMxBean : ManagementFactory.getGarbageCollectorMXBeans()) {
      long collectionTimeMs = gcMxBean.getCollectionTime();
      if (collectionTimeMs == -1) {
        // Gc collection time is not supported on this JVM.
        totalGcTimeMs = -1;
        break;
      }
      totalGcTimeMs += collectionTimeMs;
    }

    eventBus.post(new MemoryPerfStatsEvent(freeMemoryBytes, totalMemoryBytes, totalGcTimeMs));
  }

  @Override
  protected void runOneIteration() throws Exception {
    try {
      GlobalStateManager.singleton().getThreadToCommandRegister().register(
          Thread.currentThread().getId(),
          invocationInfo.getCommandId());
      probeMemory();
    } catch (Exception e) {
      Logger.get(PerfStatsTracking.class).error(e);
      throw e;
    }
  }

  @Override
  protected Scheduler scheduler() {
    return Scheduler.newFixedRateSchedule(
        1L,
        1L,
        TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    serviceManager.stopAsync();
  }

  public static class PerfStatsEvent extends AbstractBuckEvent {

    protected PerfStatsEvent() {
      super(EventKey.unique());
    }

    @Override
    protected String getValueString() {
      return "";
    }

    @Override
    public String getEventName() {
      return "";
    }
  }

  public static class MemoryPerfStatsEvent extends PerfStatsEvent {
    private final long freeMemoryBytes;
    private final long totalMemoryBytes;
    private final long timeSpentInGcMs;

    public MemoryPerfStatsEvent(
        long freeMemoryBytes,
        long totalMemoryBytes,
        long timeSpentInGcMs) {
      this.freeMemoryBytes = freeMemoryBytes;
      this.totalMemoryBytes = totalMemoryBytes;
      this.timeSpentInGcMs = timeSpentInGcMs;
    }

    public long getFreeMemoryBytes() {
      return freeMemoryBytes;
    }

    public long getTotalMemoryBytes() {
      return totalMemoryBytes;
    }

    public long getTimeSpentInGcMs() {
      return timeSpentInGcMs;
    }
  }
}
