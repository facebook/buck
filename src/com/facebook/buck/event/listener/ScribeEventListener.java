/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.build.event.BuildRuleEvent.Finished;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.network.ScribeLogger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.eventbus.Subscribe;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/** {@link BuckEventListener} that serializes events to JSON and sends them to Scribe. */
public class ScribeEventListener implements BuckEventListener {

  private static final Logger LOG = Logger.get(ScribeEventListener.class);

  private final String category;
  private final Set<String> events;
  private final ImmutableSet<String> enabledBuildRuleFinishedStatuses;
  private final boolean enabled;
  private final ScribeLogger logger;
  private final ExecutorService dispatcher;

  /**
   * Create new instance of {@link ScribeEventListener}
   *
   * @param logger The actual scribe logger used to accept messages
   * @param dispatcher Executor service used to dispatch messages
   */
  public ScribeEventListener(
      ScribeEventListenerConfig config, ScribeLogger logger, ExecutorService dispatcher) {
    this.category = config.getCategory();
    this.events = Sets.newHashSet(config.getEvents());
    this.enabled = config.getEnabled();
    this.enabledBuildRuleFinishedStatuses =
        ImmutableSet.copyOf(config.getEnabledBuildRuleFinishedStatuses());

    this.logger = logger;
    this.dispatcher = dispatcher;
  }

  private void log(BuckEvent event) {
    // Only send enabled events to Scribe. Do nothing otherwise.
    if (!enabled || !isEnabledEvent(event)) {
      return;
    }

    dispatcher.submit(
        () -> {
          try {
            String message = ObjectMappers.WRITER.writeValueAsString(event);
            logger.log(category, Arrays.asList(message));
          } catch (JsonProcessingException ex) {
            LOG.warn(ex, "Failed to create Scribe message");
          }
        });
  }

  @Subscribe
  public void handle(BuckEvent event) {
    log(event);
  }

  /** Returns true if the event should be sent to scribe; false otherwise. */
  public boolean isEnabledEvent(BuckEvent event) {
    // Only send enabled events to scribe.
    if (!events.contains(event.getEventName())) {
      return false;
    }
    // If the event is BuildRuleFinished, only send if the status is enabled.
    if (event.getEventName().equals("BuildRuleFinished")
        && !enabledBuildRuleFinishedStatuses.contains(((Finished) event).getStatus().name())) {
      return false;
    }
    return true;
  }
}
