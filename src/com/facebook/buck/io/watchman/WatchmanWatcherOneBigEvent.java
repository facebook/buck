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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import java.util.Collection;

/**
 * One event sent for all watchman changes.
 *
 * <p>Useful instead of multiple individual events for example because:
 *
 * <ul>
 *   <li>no need to acquire lock for processing each file changed
 *   <li>no need in invalidate file changes when there's an overflow event
 *   <li>possible to make better state assertions (one event sent at most once per watcher run)
 * </ul>
 */
@BuckStyleValue
public abstract class WatchmanWatcherOneBigEvent {

  public abstract ImmutableList<WatchmanPathEvent> getPathEvents();

  public abstract ImmutableList<WatchmanOverflowEvent> getOverflowEvents();

  /** Is event empty? */
  public boolean isEmpty() {
    return getPathEvents().isEmpty() && getOverflowEvents().isEmpty();
  }

  /** Create overflow event. */
  public static WatchmanWatcherOneBigEvent overflow(WatchmanOverflowEvent event) {
    return ImmutableWatchmanWatcherOneBigEvent.ofImpl(ImmutableList.of(), ImmutableList.of(event));
  }

  /** Create path event. */
  public static WatchmanWatcherOneBigEvent pathEvents(ImmutableList<WatchmanPathEvent> changes) {
    return ImmutableWatchmanWatcherOneBigEvent.ofImpl(changes, ImmutableList.of());
  }

  /** Create path event. */
  public static WatchmanWatcherOneBigEvent pathEvent(WatchmanPathEvent change) {
    return pathEvents(ImmutableList.of(change));
  }

  /** Merge multiple events into one event. */
  public static WatchmanWatcherOneBigEvent merge(Collection<WatchmanWatcherOneBigEvent> events) {
    ImmutableList.Builder<WatchmanPathEvent> pathEvents = ImmutableList.builder();
    ImmutableList.Builder<WatchmanOverflowEvent> overflows = ImmutableList.builder();
    for (WatchmanWatcherOneBigEvent event : events) {
      pathEvents.addAll(event.getPathEvents());
      overflows.addAll(event.getOverflowEvents());
    }
    return ImmutableWatchmanWatcherOneBigEvent.ofImpl(pathEvents.build(), overflows.build());
  }

  @Override
  public abstract String toString();

  /** Short description of events. */
  public String summaryForLogging() {
    // Reasonable amount to be able to debug, but keep logs small
    if (getOverflowEvents().size() + getPathEvents().size() <= 5) {
      return toString();
    } else {
      return MoreObjects.toStringHelper(WatchmanWatcherOneBigEvent.class)
          .add("pathEvents", getPathEvents().size())
          .add("overflowEvents", getOverflowEvents().size())
          .toString();
    }
  }
}
