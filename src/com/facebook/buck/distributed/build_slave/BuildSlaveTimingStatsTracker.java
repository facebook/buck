/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.BuildSlavePerStageTimingStats;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Keeps track of BuildSlave timing statistics. */
public class BuildSlaveTimingStatsTracker {

  public enum SlaveEvents {
    DIST_BUILD_PREPARATION_TIME,
    DIST_BUILD_STATE_FETCH_TIME,
    DIST_BUILD_STATE_LOADING_TIME,
    SOURCE_FILE_PRELOAD_TIME,
    TARGET_GRAPH_DESERIALIZATION_TIME,
    ACTION_GRAPH_CREATION_TIME,
    REVERSE_DEPENDENCY_QUEUE_CREATION_TIME,
    TOTAL_RUNTIME,
  }

  private final Map<SlaveEvents, Stopwatch> watches = new HashMap<>();

  public synchronized long getElapsedTimeMs(SlaveEvents event) {
    Stopwatch watch = Objects.requireNonNull(watches.get(event));
    Preconditions.checkState(!watch.isRunning(), "Stopwatch for %s is still running.", event);
    return watch.elapsed(TimeUnit.MILLISECONDS);
  }

  public synchronized void startTimer(SlaveEvents event) {
    Preconditions.checkState(
        !watches.containsKey(event), "Stopwatch for %s has already been started.", event);
    watches.put(event, Stopwatch.createStarted());
  }

  public synchronized void stopTimer(SlaveEvents event) {
    Stopwatch watch = Objects.requireNonNull(watches.get(event));
    Preconditions.checkState(
        watch.isRunning(), "Stopwatch for %s has already been stopped.", event);
    watch.stop();
  }

  public synchronized boolean isSet(SlaveEvents event) {
    return watches.containsKey(event);
  }

  public BuildSlavePerStageTimingStats generateStats() {
    BuildSlavePerStageTimingStats timingStats =
        new BuildSlavePerStageTimingStats()
            .setDistBuildPreparationTimeMillis(
                getElapsedTimeMs(SlaveEvents.DIST_BUILD_PREPARATION_TIME))
            .setDistBuildStateFetchTimeMillis(
                getElapsedTimeMs(SlaveEvents.DIST_BUILD_STATE_FETCH_TIME))
            .setDistBuildStateLoadingTimeMillis(
                getElapsedTimeMs(SlaveEvents.DIST_BUILD_STATE_LOADING_TIME))
            .setSourceFilePreloadTimeMillis(getElapsedTimeMs(SlaveEvents.SOURCE_FILE_PRELOAD_TIME))
            .setTargetGraphDeserializationTimeMillis(
                getElapsedTimeMs(SlaveEvents.TARGET_GRAPH_DESERIALIZATION_TIME))
            .setActionGraphCreationTimeMillis(
                getElapsedTimeMs(SlaveEvents.ACTION_GRAPH_CREATION_TIME))
            .setTotalBuildtimeMillis(getElapsedTimeMs(SlaveEvents.TOTAL_RUNTIME));

    if (isSet(SlaveEvents.REVERSE_DEPENDENCY_QUEUE_CREATION_TIME)) {
      timingStats.setReverseDependencyQueueCreationTimeMillis(
          getElapsedTimeMs(SlaveEvents.REVERSE_DEPENDENCY_QUEUE_CREATION_TIME));
    }

    return timingStats;
  }
}
