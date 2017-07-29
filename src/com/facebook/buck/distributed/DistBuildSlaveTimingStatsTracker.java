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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildSlavePerStageTimingStats;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DistBuildSlaveTimingStatsTracker {

  public enum SlaveEvents {
    DIST_BUILD_STATE_FETCH_TIME,
    DIST_BUILD_STATE_LOADING_TIME,
    SOURCE_FILE_PRELOAD_TIME,
    TARGET_GRAPH_DESERIALIZATION_TIME,
    ACTION_GRAPH_CREATION_TIME,
    TOTAL_RUNTIME,
  }

  private final Map<SlaveEvents, Stopwatch> watches = new HashMap<>();

  public long getElapsedTimeMs(SlaveEvents event) {
    Stopwatch watch = Preconditions.checkNotNull(watches.get(event));
    Preconditions.checkState(!watch.isRunning(), "Stopwatch for %s is still running.", event);
    return watch.elapsed(TimeUnit.MILLISECONDS);
  }

  public void startTimer(SlaveEvents event) {
    Preconditions.checkState(
        !watches.containsKey(event), "Stopwatch for %s has already been started.", event);
    watches.put(event, Stopwatch.createStarted());
  }

  public void stopTimer(SlaveEvents event) {
    Stopwatch watch = Preconditions.checkNotNull(watches.get(event));
    Preconditions.checkState(
        watch.isRunning(), "Stopwatch for %s has already been stopped.", event);
    watch.stop();
  }

  public BuildSlavePerStageTimingStats generateStats() {
    return new BuildSlavePerStageTimingStats()
        .setDistBuildStateFetchTimeMillis(getElapsedTimeMs(SlaveEvents.DIST_BUILD_STATE_FETCH_TIME))
        .setDistBuildStateLoadingTimeMillis(
            getElapsedTimeMs(SlaveEvents.DIST_BUILD_STATE_LOADING_TIME))
        .setSourceFilePreloadTimeMillis(getElapsedTimeMs(SlaveEvents.SOURCE_FILE_PRELOAD_TIME))
        .setTargetGraphDeserializationTimeMillis(
            getElapsedTimeMs(SlaveEvents.TARGET_GRAPH_DESERIALIZATION_TIME))
        .setActionGraphCreationTimeMillis(getElapsedTimeMs(SlaveEvents.ACTION_GRAPH_CREATION_TIME))
        .setTotalBuildtimeMillis(getElapsedTimeMs(SlaveEvents.TOTAL_RUNTIME));
  }
}
