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
package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class HttpCacheUploadStats {
  protected final AtomicLong httpArtifactTotalBytesUploaded = new AtomicLong(0);
  protected final AtomicInteger httpArtifactTotalUploadsScheduledCount = new AtomicInteger(0);
  protected final AtomicInteger httpArtifactUploadsOngoingCount = new AtomicInteger(0);
  protected final AtomicInteger httpArtifactUploadsSuccessCount = new AtomicInteger(0);
  protected final AtomicInteger httpArtifactUploadsFailureCount = new AtomicInteger(0);

  public void processHttpArtifactCacheScheduledEvent(HttpArtifactCacheEvent.Scheduled event) {
    if (event.getOperation() != ArtifactCacheEvent.Operation.STORE) {
      return;
    }

    httpArtifactTotalUploadsScheduledCount.incrementAndGet();
  }

  public void processHttpArtifactCacheStartedEvent(HttpArtifactCacheEvent.Started event) {
    if (event.getOperation() != ArtifactCacheEvent.Operation.STORE) {
      return;
    }

    httpArtifactUploadsOngoingCount.incrementAndGet();
  }

  public void processHttpArtifactCacheFinishedEvent(HttpArtifactCacheEvent.Finished event) {
    if (event.getOperation() != ArtifactCacheEvent.Operation.STORE) {
      return;
    }

    httpArtifactUploadsOngoingCount.decrementAndGet();
    if (event.getStoreData().wasStoreSuccessful().orElse(false)) {
      httpArtifactUploadsSuccessCount.incrementAndGet();
      Optional<Long> artifactSizeBytes = event.getStoreData().getArtifactSizeBytes();
      if (artifactSizeBytes.isPresent()) {
        httpArtifactTotalBytesUploaded.addAndGet(artifactSizeBytes.get());
      }
    } else {
      httpArtifactUploadsFailureCount.incrementAndGet();
    }
  }

  public long getHttpArtifactTotalBytesUploaded() {
    return httpArtifactTotalBytesUploaded.get();
  }

  public int getHttpArtifactTotalUploadsScheduledCount() {
    return httpArtifactTotalUploadsScheduledCount.get();
  }

  public int getHttpArtifactUploadsOngoingCount() {
    return httpArtifactUploadsOngoingCount.get();
  }

  public int getHttpArtifactUploadsSuccessCount() {
    return httpArtifactUploadsSuccessCount.get();
  }

  public int getHttpArtifactUploadsFailureCount() {
    return httpArtifactUploadsFailureCount.get();
  }
}
