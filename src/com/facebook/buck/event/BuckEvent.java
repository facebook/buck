/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.event;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import java.util.Objects;

/**
 * Base class for all build events. Using this makes it easy to add a wildcard listener
 * to the event bus.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class BuckEvent {

  private boolean isConfigured;
  private long timestamp;
  private long nanoTime;
  private long threadId;

  protected BuckEvent() {
    isConfigured = false;
  }

  /**
   * Method to configure an event before posting it to the {@link BuckEventBus}.  This method should
   * only be invoked once per event, and only by the {@link BuckEventBus} in production code.
   */
  @VisibleForTesting
  public void configure(long timestamp, long nanoTime, long threadId) {
    Preconditions.checkState(!isConfigured, "Events can only be configured once.");
    this.timestamp = timestamp;
    this.nanoTime = nanoTime;
    this.threadId = threadId;
    isConfigured = true;
  }

  public long getTimestamp() {
    Preconditions.checkState(isConfigured, "Event was not configured yet.");
    return timestamp;
  }

  public long getNanoTime() {
    Preconditions.checkState(isConfigured, "Event was not configured yet.");
    return nanoTime;
  }

  public String toLogMessage() {
    return toString();
  }

  public long getThreadId() {
    Preconditions.checkState(isConfigured, "Event was not configured yet.");
    return threadId;
  }

  @Override
  public String toString() {
    return String.format("%s(%s)", getEventName(), getValueString());
  }

  /**
   * @return Whether or not this event is a pair of another event.  Events that are pairs if they
   * pertain to the same event, for example if they are measuring the start and stop of some phase.
   * For example,
   * <pre>
   *   <code>
   *    (CommandEvent.started("build")).eventsArePair(CommandEvent.finished("build")) == true
   *    (CommandEvent.started("build")).eventsArePair(CommandEvent.started("build")) == true
   *    (CommandEvent.started("build")).eventsArePair(CommandEvent.finished("install")) == false
   *   </code>
   * </pre>
   * This should be used to pair start events to finished events.
   */
  abstract public boolean eventsArePair(BuckEvent event);

  abstract protected String getEventName();

  abstract protected String getValueString();

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BuckEvent)) {
      return false;
    }

    BuckEvent that = (BuckEvent)o;

    return eventsArePair(that) &&
        getThreadId() == that.getThreadId() &&
        Objects.equals(getClass(), that.getClass());
  }

  @Override
  public int hashCode() {
    return Objects.hash(threadId);
  }
}
