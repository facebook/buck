/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.event.utils;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.protobuf.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Utility methods that help to operate with event buses ({@link
 * com.facebook.buck.event.BuckEventBus}, {@link com.facebook.buck.event.IsolatedEventBus})
 */
public class EventBusUtils {

  private EventBusUtils() {}

  /** Converts millis into {@link Duration} */
  public static Duration millisToDuration(long millis) {
    long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
    long secondsInMillis = TimeUnit.SECONDS.toMillis(seconds);
    int nanos = (int) TimeUnit.MILLISECONDS.toNanos(millis - secondsInMillis);
    return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  /** Configures event using passed {@code atTime}, {@code threadId} and {@code buildId} */
  public static void configureEvent(
      BuckEvent event, Instant atTime, long threadId, Clock clock, BuildId buildId) {
    long currentMillis = clock.currentTimeMillis();
    long currentNanos = clock.nanoTime();
    long threadUserNanoTime = clock.threadUserNanoTime(threadId);

    long eventTimeInMillis = atTime.toEpochMilli();
    // event occurred in the past
    Preconditions.checkState(currentMillis >= eventTimeInMillis);

    long adjustedToEpochNanos = currentNanos - TimeUnit.MILLISECONDS.toNanos(eventTimeInMillis);
    long eventNanos =
        adjustedToEpochNanos + TimeUnit.SECONDS.toNanos(atTime.getEpochSecond()) + atTime.getNano();

    event.configure(eventTimeInMillis, eventNanos, threadUserNanoTime, threadId, buildId);
  }
}
