/*
 * Copyright 2013-present Facebook, Inc.
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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;

public interface BuckEvent {
  @VisibleForTesting
  void configure(long timestamp, long nanoTime, long threadId, BuildId buildId);

  long getTimestamp();

  long getNanoTime();

  String toLogMessage();

  long getThreadId();

  /**
   * @return an identifier that distinguishes the build with which this event is associated.
   */
  BuildId getBuildId();

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
  boolean eventsArePair(BuckEvent event);

  @JsonProperty("type")
  public String getEventName();
}
