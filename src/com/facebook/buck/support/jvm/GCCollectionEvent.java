/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.support.jvm;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.collect.ImmutableMap;
import com.sun.management.GcInfo;
import java.lang.management.MemoryUsage;
import java.util.Map;

/**
 * Base class for GC collection events emitting by {@link GCNotificationEventEmitter}. Subclasses
 * contain information about the nature of this collection (major or minor), while this class
 * contains information about the duration and memory statistics of this GC.
 */
public abstract class GCCollectionEvent extends AbstractBuckEvent {
  /**
   * The GC ID of this GC, as assigned by the runtime. The runtime assigns monotonically increasing
   * numbers to GCs.
   */
  private final long id;

  /**
   * The durationInMillis of this GC, in milliseconds. The definition of "durationInMillis" depends
   * on GC to GC but for G1 it generally corresponds to time that the program is paused.
   */
  private final long durationInMillis;

  /** Memory usage statistics for this process prior to the start of the GC. */
  private final ImmutableMap<String, ImmutableMap<String, Long>> memoryUsageBeforeGC;

  /** Memory usage statistics for this process after the end of the GC. */
  private final ImmutableMap<String, ImmutableMap<String, Long>> memoryUsageAfterGC;

  GCCollectionEvent(EventKey eventKey, GcInfo info) {
    super(eventKey);
    this.id = info.getId();
    this.durationInMillis = info.getDuration();
    this.memoryUsageBeforeGC = memoryUsageMap(info.getMemoryUsageBeforeGc());
    this.memoryUsageAfterGC = memoryUsageMap(info.getMemoryUsageAfterGc());
  }

  @JsonView(JsonViews.MachineReadableLog.class)
  public long getId() {
    return id;
  }

  @JsonView(JsonViews.MachineReadableLog.class)
  public long getDurationInMillis() {
    return durationInMillis;
  }

  @JsonView(JsonViews.MachineReadableLog.class)
  public ImmutableMap<String, ImmutableMap<String, Long>> getMemoryUsageBeforeGC() {
    return memoryUsageBeforeGC;
  }

  @JsonView(JsonViews.MachineReadableLog.class)
  public ImmutableMap<String, ImmutableMap<String, Long>> getMemoryUsageAfterGC() {
    return memoryUsageAfterGC;
  }

  @Override
  protected String getValueString() {
    return "";
  }

  /**
   * Transforms the {@link MemoryUsage} struct given to us from JMX into a serializable map.
   * MemoryUsage gives us three things:
   *
   * <ul>
   *   <li>1. "Committed", the amount of virtual memory committed in a particular zone
   *   <li>2. "Used", the amount of virtual memory actually in use in a particular zone
   *   <li>3. "Max", the maximum amount of virtual memory this JVM is allowed to commit at any point
   *       in time,
   *   <li>4. "Init", the amount of virtual memory this JVM committed on startup.
   * </ul>
   *
   * <p>Max and init can change at runtime, but they generally don't (they are JVM startup
   * parameters) so this function omits them from the map.
   *
   * <p>Each GC reports memory usage separately for each zone. The JVM has a lot of zones, so this
   * may be noisy and full of things that we don't care about (e.g. code heap). We might want to
   * tune this as we go.
   *
   * @param usage Memory usage map per zone
   * @return Serializable map of the memory usage
   */
  private static ImmutableMap<String, ImmutableMap<String, Long>> memoryUsageMap(
      Map<String, MemoryUsage> usage) {
    ImmutableMap.Builder<String, ImmutableMap<String, Long>> builder = ImmutableMap.builder();
    for (Map.Entry<String, MemoryUsage> entry : usage.entrySet()) {
      ImmutableMap<String, Long> usageData =
          ImmutableMap.<String, Long>builder()
              .put("committed", entry.getValue().getCommitted())
              .put("used", entry.getValue().getUsed())
              .build();
      builder.put(entry.getKey(), usageData);
    }

    return builder.build();
  }
}
