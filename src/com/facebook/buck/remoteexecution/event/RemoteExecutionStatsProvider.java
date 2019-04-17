/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.event;

import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.google.common.collect.ImmutableMap;

/** Provides statistics about the ongoing remote execution flow. */
public interface RemoteExecutionStatsProvider {

  /** Current state of all remote execution Actions. */
  ImmutableMap<State, Integer> getActionsPerState();

  /** Total number of downloads. */
  int getCasDownloads();

  /** Total number of downloaded bytes from CAS. */
  long getCasDownloadSizeBytes();

  /** Total number of downloads. */
  int getCasUploads();

  /** Total of uploaded bytes to CAS. */
  long getCasUploadSizeBytes();

  /** Get the total number of BuildRules that are finished. (both local and remote) */
  int getTotalRulesBuilt();

  /** Fetches stats regarding the local fallback. */
  LocalFallbackStats getLocalFallbackStats();

  /** Metadata for total time spent executing actions remotely in millis. */
  long getRemoteCpuTimeMs();

  /** Metadata for total time spent queued for executing actions in millis. */
  long getRemoteQueueTimeMs();

  /** Metadata for total time spent running actions remotely. */
  long getTotalRemoteTimeMs();

  /** Export all the above metadata in a Map format */
  ImmutableMap<String, String> exportFieldsToMap();
}
