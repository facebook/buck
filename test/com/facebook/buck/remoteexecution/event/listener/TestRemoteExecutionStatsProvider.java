/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.remoteexecution.event.listener;

import com.facebook.buck.remoteexecution.event.LocalFallbackStats;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.facebook.buck.remoteexecution.event.RemoteExecutionStatsProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class TestRemoteExecutionStatsProvider implements RemoteExecutionStatsProvider {
  public Map<State, Integer> actionsPerState = Maps.newHashMap();
  public int casDownloads = 0;
  public int casDownladedBytes = 0;
  public LocalFallbackStats localFallbackStats =
      LocalFallbackStats.builder()
          .setTotalExecutedRules(84)
          .setLocallyExecutedRules(42)
          .setLocallySuccessfulRules(21)
          .build();

  public TestRemoteExecutionStatsProvider() {
    for (State state : State.values()) {
      actionsPerState.put(state, new Integer(0));
    }
  }

  @Override
  public ImmutableMap<State, Integer> getActionsPerState() {
    return ImmutableMap.copyOf(actionsPerState);
  }

  @Override
  public int getCasDownloads() {
    return casDownloads;
  }

  @Override
  public long getCasDownloadSizeBytes() {
    return casDownladedBytes;
  }

  @Override
  public int getCasUploads() {
    return 0;
  }

  @Override
  public long getCasUploadSizeBytes() {
    return 0;
  }

  @Override
  public int getTotalRulesBuilt() {
    return 0;
  }

  @Override
  public LocalFallbackStats getLocalFallbackStats() {
    return localFallbackStats;
  }

  @Override
  public ImmutableMap<String, String> exportFieldsToMap() {
    ImmutableMap.Builder<String, String> retval = ImmutableMap.builderWithExpectedSize(16);

    retval
        .put("cas_downloads_count", Integer.toString(getCasDownloads()))
        .put("cas_downloads_bytes", Long.toString(getCasDownloadSizeBytes()))
        .put("cas_uploads_count", Integer.toString(getCasUploads()))
        .put("cas_uploads_bytes", Long.toString(getCasUploadSizeBytes()))
        .put(
            "localfallback_totally_executed_rules",
            Integer.toString(localFallbackStats.getTotalExecutedRules()))
        .put(
            "localfallback_locally_executed_rules",
            Integer.toString(localFallbackStats.getLocallyExecutedRules()))
        .put(
            "localfallback_locally_successful_executed_rules",
            Integer.toString(localFallbackStats.getLocallySuccessfulRules()))
        .put("remote_cpu_time_sec", Long.toString(getRemoteCpuTimeMs()))
        .put("remote_queue_time_sec", Long.toString(getRemoteQueueTimeMs()));

    for (ImmutableMap.Entry<State, Integer> entry : getActionsPerState().entrySet()) {
      retval.put(
          String.format("remote_state_%s_count", entry.getKey().getAbbreviateName()),
          Integer.toString(entry.getValue()));
    }

    return retval.build();
  }

  @Override
  public long getRemoteCpuTimeMs() {
    return TimeUnit.SECONDS.toMillis(65);
  }

  @Override
  public long getRemoteQueueTimeMs() {
    return 0;
  }

  @Override
  public long getTotalRemoteTimeMs() {
    return 0;
  }
}
