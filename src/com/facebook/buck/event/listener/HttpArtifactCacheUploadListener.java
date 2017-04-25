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

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.counters.CounterSnapshot;
import com.facebook.buck.counters.CountersSnapshotEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.BuildEvent;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;

/**
 * In charge for monitoring builds that store artifacts to the remote cache and computing stateful
 * cache upload stats.
 */
public class HttpArtifactCacheUploadListener implements BuckEventListener {

  private final BuckEventBus eventBus;
  private final int uploadThreadCount;

  private int outstandingUploads;
  private long lastUploadStartMillis;
  private long firstUploadMillis;
  private long buildFinishMillis;
  private long lastUploadFinishMillis;
  private boolean hasCounterBeenSent;

  private int artifactCount;
  private long totalUploadedBytes;
  private long totalNetworkTimeMillis;

  public HttpArtifactCacheUploadListener(BuckEventBus eventBus, int uploadThreadCount) {
    this.eventBus = eventBus;
    this.uploadThreadCount = uploadThreadCount;
    this.outstandingUploads = 0;
    this.lastUploadStartMillis = -1;
    this.firstUploadMillis = -1;
    this.buildFinishMillis = -1;
    this.lastUploadFinishMillis = 0;
    this.hasCounterBeenSent = false;
    this.artifactCount = 0;
    this.totalUploadedBytes = 0;
    this.totalNetworkTimeMillis = -1;
  }

  @Subscribe
  public synchronized void onArtifactUploadStart(HttpArtifactCacheEvent.Started event) {
    if (event.getOperation() != ArtifactCacheEvent.Operation.STORE) {
      return;
    }

    ++outstandingUploads;
    if (outstandingUploads == 1) {
      lastUploadStartMillis = event.getTimestamp();
    }
    if (firstUploadMillis == -1) {
      firstUploadMillis = event.getTimestamp();
    }
  }

  @Subscribe
  public synchronized void onArtifactUploadFinish(HttpArtifactCacheEvent.Finished event) {
    if (event.getOperation() != ArtifactCacheEvent.Operation.STORE) {
      return;
    }

    --outstandingUploads;
    ++artifactCount;
    if (event.getStoreData().getArtifactSizeBytes().isPresent()) {
      totalUploadedBytes += event.getStoreData().getArtifactSizeBytes().get();
    }
    lastUploadFinishMillis = event.getTimestamp();
    if (outstandingUploads == 0) {
      totalNetworkTimeMillis += event.getTimestamp() - lastUploadStartMillis;
    }

    sendCounterSnapshotIfFinished();
  }

  @Subscribe
  public synchronized void onBuildFinished(BuildEvent.Finished event) {
    buildFinishMillis = event.getTimestamp();
    sendCounterSnapshotIfFinished();
  }

  private synchronized void sendCounterSnapshotIfFinished() {
    if (!hasCounterBeenSent
        && artifactCount > 0
        && outstandingUploads == 0
        && buildFinishMillis != -1) {
      hasCounterBeenSent = true;
      CounterSnapshot snapshot = generateCounterSnapshot().build();
      eventBus.post(new CountersSnapshotEvent(Lists.newArrayList(snapshot)));
    }
  }

  private CounterSnapshot.Builder generateCounterSnapshot() {
    CounterSnapshot.Builder builder =
        CounterSnapshot.builder()
            .setCategory("buck_http_cache_upload_stats")
            .putValues("upload_thread_count", uploadThreadCount)
            .putValues("artifact_count", artifactCount)
            .putValues("total_uploaded_bytes", totalUploadedBytes);

    if (totalNetworkTimeMillis != -1) {
      builder.putValues("total_network_time_millis", totalNetworkTimeMillis);
    }

    if (totalNetworkTimeMillis > 0) {
      builder.putValues(
          "average_bytes_per_second", (1000 * totalUploadedBytes) / totalNetworkTimeMillis);
    }

    if (buildFinishMillis != -1 && lastUploadFinishMillis != -1) {
      builder.putValues(
          "elapsed_upload_time_after_build_finished_millis",
          buildFinishMillis - lastUploadFinishMillis);
    }

    return builder;
  }

  @Override
  public synchronized void outputTrace(BuildId buildId) {
    // No-op.
  }
}
