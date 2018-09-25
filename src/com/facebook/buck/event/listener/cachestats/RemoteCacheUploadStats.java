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
package com.facebook.buck.event.listener.cachestats;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Count artifact's upload metrics based on events. */
public class RemoteCacheUploadStats {

  private final AtomicLong totalBytesUploaded = new AtomicLong(0);
  private final AtomicInteger scheduledCount = new AtomicInteger(0);
  private final AtomicInteger ongoingCount = new AtomicInteger(0);
  private final AtomicInteger successCount = new AtomicInteger(0);
  private final AtomicInteger failureCount = new AtomicInteger(0);

  /**
   * Process an scheduled upload event.
   *
   * @param event the scheduled event.
   */
  public void processScheduledEvent(HttpArtifactCacheEvent.Scheduled event) {
    if (event.getOperation() != ArtifactCacheEvent.Operation.STORE) {
      return;
    }

    scheduledCount.incrementAndGet();
  }

  /**
   * Process an started upload event.
   *
   * @param event the started event.
   */
  public void processStartedEvent(HttpArtifactCacheEvent.Started event) {
    if (event.getOperation() != ArtifactCacheEvent.Operation.STORE) {
      return;
    }

    ongoingCount.incrementAndGet();
  }

  /**
   * Process an finished upload event.
   *
   * @param event the finished event.
   */
  public void processFinishedEvent(HttpArtifactCacheEvent.Finished event) {
    if (event.getOperation() != ArtifactCacheEvent.Operation.STORE) {
      return;
    }

    ongoingCount.decrementAndGet();
    if (event.getStoreData().wasStoreSuccessful().orElse(false)) {
      successCount.incrementAndGet();
      event.getStoreData().getArtifactSizeBytes().ifPresent(totalBytesUploaded::addAndGet);
    } else {
      failureCount.incrementAndGet();
    }
  }

  public void incrementScheduledCount() {
    scheduledCount.incrementAndGet();
  }

  public void incrementSuccessCount() {
    successCount.incrementAndGet();
  }

  public void incrementFailureCount() {
    failureCount.incrementAndGet();
  }

  public long getBytesUploaded() {
    return totalBytesUploaded.get();
  }

  public int getScheduledCount() {
    return scheduledCount.get();
  }

  public int getOngoingCount() {
    return ongoingCount.get();
  }

  public int getSuccessCount() {
    return successCount.get();
  }

  public int getFailureCount() {
    return failureCount.get();
  }
}
