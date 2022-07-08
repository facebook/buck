/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.remoteexecution.event;

import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.google.common.collect.ImmutableMap;

/** Provides statistics about the ongoing remote execution flow. */
public interface RemoteExecutionStatsProvider {

  /** Current state of all remote execution Actions. */
  ImmutableMap<State, Integer> getActionsPerState();

  /**
   * Total number of downloads. This is equal to: 1) getRemoteExecutionCasDownloads() +
   * getArtifactCacheCasDownloads() 2) getCasSmallDownloads() + getCasLargeDownloads()
   */
  int getCasDownloads();

  /** Number of "small" downloads (these downloads usually go to zippy). */
  int getCasSmallDownloads();

  /** Number of "large" downloads (these downloads usually go to manifold). */
  int getCasLargeDownloads();

  /** Number of "small" downloaded bytes from CAS. */
  long getCasSmallDownloadSizeBytes();

  /** Number of "large" downloaded bytes from CAS. */
  long getCasLargeDownloadSizeBytes();

  /**
   * Total number of downloaded bytes from CAS. This is equal to: 1)
   * getRemoteExecutionCasDownloadSizeBytes() + getArtifactCacheCasDownloadSizeBytes() 2)
   * getCasSmallDownloadSizeBytes() + getCasLargeDownloadSizeBytes()
   */
  long getCasDownloadSizeBytes();

  /**
   * CAS can be used as an artifact cache or as a storage of Remote execution inputs and outputs.
   * This method returns number of downloads for CAS used as an artifact cache.
   */
  int getArtifactCacheCasDownloads();

  /**
   * Size of CAS downloads for artifact cache. See comment above for difference between "CAS for
   * artifact cache" and "CAS for remote execution"
   */
  long getArtifactCacheCasDownloadSizeBytes();

  /**
   * CAS downloads for artifact cache. See comment above for difference between "CAS for artifact
   * cache" and "CAS for remote execution"
   */
  int getRemoteExecutionCasDownloads();

  /**
   * Size of CAS downloads for artifact cache. See comment above for difference between "CAS for
   * artifact cache" and "CAS for remote execution"
   */
  long getRemoteExecutionCasDownloadSizeBytes();

  /**
   * Total number of uploads. This is equal to 1) getRemoteExecutionCasUploads() +
   * getArtifactCacheCasUploads() 2) getCasSmallUploads() + getCasLargeUploads()
   */
  int getCasUploads();

  /** Number of "small" uploads (these uploads usually go to zippy). */
  int getCasSmallUploads();

  /** Number of "large" uploads (these uploads usually go to manifold). */
  int getCasLargeUploads();

  /** Number of "small" uploaded bytes from CAS. */
  long getCasSmallUploadSizeBytes();

  /** Number of "large" uploaded bytes from CAS. */
  long getCasLargeUploadSizeBytes();

  /**
   * Total of uploaded bytes to CAS. This is equal to: 1) getRemoteExecutionCasUploadSizeBytes() +
   * getArtifactCacheCasUploadSizeBytes() 2) getCasSmallUploadSizeBytes() +
   * getCasLargeUploadSizeBytes()
   */
  long getCasUploadSizeBytes();

  /**
   * CAS uploads for artifact cache. See comment above for difference between "CAS for artifact
   * cache" and "CAS for remote execution"
   */
  int getArtifactCacheCasUploads();

  /**
   * Size of CAS uploads for artifact cache. See comment above for difference between "CAS for
   * artifact cache" and "CAS for remote execution"
   */
  long getArtifactCacheCasUploadSizeBytes();

  /**
   * CAS uploads for artifact cache. See comment above for difference between "CAS for artifact
   * cache" and "CAS for remote execution"
   */
  int getRemoteExecutionCasUploads();

  /**
   * Size of CAS uploads for artifact cache. See comment above for difference between "CAS for
   * artifact cache" and "CAS for remote execution"
   */
  long getRemoteExecutionCasUploadSizeBytes();

  /** Total number of digests that were sent in findMissing calls. */
  long getCasFindMissingCount();

  /** Number of small digests that were sent in findMissing calls. */
  long getCasFindMissingSmallCount();

  /** Number of large digests that were sent in findMissing calls. */
  long getCasFindMissingLargeCount();

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

  // Weighted mem usage: sum(used_mem) / sum(total_avaialble_mem)
  float getWeightedMemUsage();

  // Amount of memory that were used on the remote workers
  long getTotalUsedRemoteMemory();

  // Amount of memory that were available on the remote workers
  long getTotalAvailableRemoteMemory();

  // Amount of memory that were available for the task
  long getTaskTotalAvailableRemoteMemory();

  /** Export all the above metadata in a Map format */
  ImmutableMap<String, String> exportFieldsToMap();
}
