/*
 * Copyright 2015-present Facebook, Inc.
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

import static com.facebook.buck.event.TestEventConfigerator.configureTestEventAtTime;

import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.RuleKey;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.util.concurrent.TimeUnit;

public class ConsoleTestUtils {

  private ConsoleTestUtils() {}

  public static HttpArtifactCacheEvent.Scheduled postStoreScheduled(
      BuckEventBus eventBus,
      long threadId,
      String target,
      long timeInMs) {
    HttpArtifactCacheEvent.Scheduled storeScheduled =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            Optional.of(target), ImmutableSet.<RuleKey>of());

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            storeScheduled,
            timeInMs,
            TimeUnit.MILLISECONDS,
            threadId));
    return storeScheduled;
  }

  public static HttpArtifactCacheEvent.Started postStoreStarted(
      BuckEventBus eventBus,
      long threadId,
      long timeInMs,
      HttpArtifactCacheEvent.Scheduled storeScheduled) {
    HttpArtifactCacheEvent.Started storeStartedOne =
        HttpArtifactCacheEvent.newStoreStartedEvent(storeScheduled);

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            storeStartedOne,
            timeInMs,
            TimeUnit.MILLISECONDS,
            threadId));
    return storeStartedOne;
  }

  public static void postStoreFinished(
      BuckEventBus eventBus,
      long threadId,
      long artifactSizeInBytes,
      long timeInMs,
      boolean success,
      HttpArtifactCacheEvent.Started storeStartedOne) {
    HttpArtifactCacheEvent.Finished storeFinished =
        HttpArtifactCacheEvent.newFinishedEventBuilder(storeStartedOne)
            .setWasUploadSuccessful(success)
            .setArtifactSizeBytes(artifactSizeInBytes)
            .build();

    eventBus.postWithoutConfiguring(
        configureTestEventAtTime(
            storeFinished,
            timeInMs,
            TimeUnit.MILLISECONDS,
            threadId));
  }
}
