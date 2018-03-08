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

import com.facebook.buck.artifact_cache.ArtifactCacheEvent.StoreType;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEventStoreData;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class HttpCacheUploadStatsTest {
  private static final long ARTIFACT_ONE_BYTES = 10;
  private static final long ARTIFACT_TWO_BYTES = 20;
  private static final long ARTIFACT_ONE_AND_TWO_TOTAL_BYTES = 30;

  @Test
  public void testCacheUploadEvents() {
    HttpCacheUploadStats uploadStats = new HttpCacheUploadStats();

    // Schedule, start, and finish upload event one.

    HttpArtifactCacheEvent.Scheduled scheduledEventOne =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            Optional.of("fake"), ImmutableSet.of(), StoreType.ARTIFACT);

    uploadStats.processHttpArtifactCacheScheduledEvent(scheduledEventOne);
    Assert.assertEquals(1, uploadStats.getHttpArtifactTotalUploadsScheduledCount());

    HttpArtifactCacheEvent.Started startedEventOne =
        HttpArtifactCacheEvent.newStoreStartedEvent(scheduledEventOne);
    uploadStats.processHttpArtifactCacheStartedEvent(startedEventOne);
    Assert.assertEquals(1, uploadStats.getHttpArtifactTotalUploadsScheduledCount());
    Assert.assertEquals(1, uploadStats.getHttpArtifactUploadsOngoingCount());

    HttpArtifactCacheEvent.Finished finishedEventOne =
        createFinishedEvent(startedEventOne, true, ARTIFACT_ONE_BYTES);
    uploadStats.processHttpArtifactCacheFinishedEvent(finishedEventOne);
    Assert.assertEquals(1, uploadStats.getHttpArtifactTotalUploadsScheduledCount());
    Assert.assertEquals(0, uploadStats.getHttpArtifactUploadsOngoingCount());
    Assert.assertEquals(1, uploadStats.getHttpArtifactUploadsSuccessCount());

    // Schedule, start, finish, events two and three
    HttpArtifactCacheEvent.Scheduled scheduledEventTwo =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            Optional.of("fake"), ImmutableSet.of(), StoreType.ARTIFACT);
    HttpArtifactCacheEvent.Scheduled scheduledEventThree =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            Optional.of("fake"), ImmutableSet.of(), StoreType.ARTIFACT);
    uploadStats.processHttpArtifactCacheScheduledEvent(scheduledEventTwo);
    uploadStats.processHttpArtifactCacheScheduledEvent(scheduledEventTwo);
    Assert.assertEquals(3, uploadStats.getHttpArtifactTotalUploadsScheduledCount());
    Assert.assertEquals(0, uploadStats.getHttpArtifactUploadsOngoingCount());
    Assert.assertEquals(1, uploadStats.getHttpArtifactUploadsSuccessCount());

    HttpArtifactCacheEvent.Started startedEventTwo =
        HttpArtifactCacheEvent.newStoreStartedEvent(scheduledEventTwo);
    HttpArtifactCacheEvent.Started startedEventThree =
        HttpArtifactCacheEvent.newStoreStartedEvent(scheduledEventThree);
    uploadStats.processHttpArtifactCacheStartedEvent(startedEventTwo);
    uploadStats.processHttpArtifactCacheStartedEvent(startedEventThree);
    Assert.assertEquals(3, uploadStats.getHttpArtifactTotalUploadsScheduledCount());
    Assert.assertEquals(2, uploadStats.getHttpArtifactUploadsOngoingCount());
    Assert.assertEquals(1, uploadStats.getHttpArtifactUploadsSuccessCount());

    HttpArtifactCacheEvent.Finished finishedEventTwo =
        createFinishedEvent(startedEventTwo, true, ARTIFACT_TWO_BYTES);
    HttpArtifactCacheEvent.Finished finishedEventThree =
        createFinishedEvent(startedEventThree, false, 0);
    uploadStats.processHttpArtifactCacheFinishedEvent(finishedEventTwo);
    uploadStats.processHttpArtifactCacheFinishedEvent(finishedEventThree);
    Assert.assertEquals(3, uploadStats.getHttpArtifactTotalUploadsScheduledCount());
    Assert.assertEquals(0, uploadStats.getHttpArtifactUploadsOngoingCount());
    Assert.assertEquals(2, uploadStats.getHttpArtifactUploadsSuccessCount());
    Assert.assertEquals(1, uploadStats.getHttpArtifactUploadsFailureCount());
    Assert.assertEquals(
        ARTIFACT_ONE_AND_TWO_TOTAL_BYTES, uploadStats.getHttpArtifactTotalBytesUploaded());
  }

  private static HttpArtifactCacheEvent.Finished createFinishedEvent(
      HttpArtifactCacheEvent.Started startedEvent, boolean wasSuccessful, long artifactSizeBytes) {
    HttpArtifactCacheEvent.Finished.Builder finishedEventBuilder =
        HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent);
    HttpArtifactCacheEventStoreData.Builder storeDataBuilder =
        finishedEventBuilder.getStoreBuilder();

    storeDataBuilder.setWasStoreSuccessful(wasSuccessful).setStoreType(StoreType.ARTIFACT);
    if (wasSuccessful) {
      storeDataBuilder.setArtifactSizeBytes(artifactSizeBytes);
    }
    return finishedEventBuilder.build();
  }
}
