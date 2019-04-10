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
  public long getRemoteCpuTimeMs() {
    return TimeUnit.SECONDS.toMillis(65);
  }
}
