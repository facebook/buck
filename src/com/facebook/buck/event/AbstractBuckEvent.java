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

import com.facebook.buck.model.BuildId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import java.util.Objects;

/**
 * Base class for all build events. Using this makes it easy to add a wildcard listener
 * to the event bus.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class AbstractBuckEvent implements BuckEvent {

  private boolean isConfigured;
  private long timestamp;
  private long nanoTime;
  private long threadId;
  private BuildId buildId;

  protected AbstractBuckEvent() {
    isConfigured = false;
  }

  /**
   * Method to configure an event before posting it to the {@link BuckEventBus}.  This method should
   * only be invoked once per event, and only by the {@link BuckEventBus} in production code.
   */
  @Override
  @VisibleForTesting
  public void configure(long timestamp, long nanoTime, long threadId, BuildId buildId) {
    Preconditions.checkState(!isConfigured, "Events can only be configured once.");
    this.timestamp = timestamp;
    this.nanoTime = nanoTime;
    this.threadId = threadId;
    this.buildId = buildId;
    isConfigured = true;
  }

  @Override
  public long getTimestamp() {
    Preconditions.checkState(isConfigured, "Event was not configured yet.");
    return timestamp;
  }

  @Override
  public long getNanoTime() {
    Preconditions.checkState(isConfigured, "Event was not configured yet.");
    return nanoTime;
  }

  @Override
  public String toLogMessage() {
    return toString();
  }

  @Override
  public long getThreadId() {
    Preconditions.checkState(isConfigured, "Event was not configured yet.");
    return threadId;
  }

  @Override
  public BuildId getBuildId() {
    Preconditions.checkState(isConfigured, "Event was not configured yet.");
    return buildId;
  }

  @Override
  public String toString() {
    return String.format("%s(%s)", getEventName(), getValueString());
  }

  @JsonIgnore
  protected abstract String getValueString();

  /**
   * The default implementation of equals checks to see if two events are related, are on the same
   * thread, and are the same concrete class.  Subclasses therefore can simply override isRelatedTo,
   * and the equals method will work correctly.
   */
  @Override
  public boolean equals(Object o) {
    if (!(o instanceof AbstractBuckEvent)) {
      return false;
    }

    AbstractBuckEvent that = (AbstractBuckEvent) o;

    return isRelatedTo(that) &&
        getThreadId() == that.getThreadId() &&
        Objects.equals(getClass(), that.getClass());
  }

  @Override
  public int hashCode() {
    return Objects.hash(threadId);
  }
}
