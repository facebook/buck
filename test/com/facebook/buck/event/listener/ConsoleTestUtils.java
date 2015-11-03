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
import com.facebook.buck.rules.RuleKey;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;

import java.util.concurrent.TimeUnit;

public class ConsoleTestUtils {
  private ConsoleTestUtils() {
  }

  public static HttpArtifactCacheEvent.Scheduled postStoreScheduled(
      EventBus rawEventBus, long threadId, String target, long time) {
    HttpArtifactCacheEvent.Scheduled storeScheduled =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            Optional.of(target), ImmutableSet.<RuleKey>of());

    rawEventBus.post(configureTestEventAtTime(
            storeScheduled,
            time,
            TimeUnit.MILLISECONDS,
            threadId));
    return storeScheduled;
  }

  public static HttpArtifactCacheEvent.Started postStoreStarted(
      EventBus rawEventBus,
      long threadId,
      long time,
      HttpArtifactCacheEvent.Scheduled storeScheduled) {
    HttpArtifactCacheEvent.Started storeStartedOne =
        HttpArtifactCacheEvent.newStoreStartedEvent(storeScheduled);

    rawEventBus.post(configureTestEventAtTime(
            storeStartedOne,
            time,
            TimeUnit.MILLISECONDS,
            threadId));
    return storeStartedOne;
  }

  public static void postStoreFinished(
      EventBus rawEventBus,
      long threadId,
      long time,
      boolean success,
      HttpArtifactCacheEvent.Started storeStartedOne) {
    HttpArtifactCacheEvent.Finished storeFinished =
        HttpArtifactCacheEvent.newFinishedEventBuilder(storeStartedOne)
            .setWasUploadSuccessful(success)
            .build();

    rawEventBus.post(configureTestEventAtTime(
            storeFinished,
            time,
            TimeUnit.MILLISECONDS,
            threadId));
  }
}
