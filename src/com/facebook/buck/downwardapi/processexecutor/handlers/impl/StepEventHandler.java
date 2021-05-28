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

package com.facebook.buck.downwardapi.processexecutor.handlers.impl;

import static com.facebook.buck.event.StepEvent.finished;
import static com.facebook.buck.event.StepEvent.started;

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.downward.model.StepEvent;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.downwardapi.processexecutor.handlers.EventHandler;
import com.facebook.buck.event.StepEvent.Started;
import java.time.Instant;
import java.util.Objects;

/** Downward API event handler for {@code StepEvent} */
enum StepEventHandler implements EventHandler<StepEvent> {
  INSTANCE;

  @Override
  public void handleEvent(DownwardApiExecutionContext context, StepEvent event) {
    Instant timestamp = EventHandlerUtils.getTimestamp(context, event.getDuration());
    int eventId = event.getEventId();
    ActionId actionId = ActionId.of(event.getActionId());

    switch (event.getStepStatus()) {
      case STARTED:
        Started startedEvent = started(event.getStepType(), event.getDescription());
        context.registerStartStepEvent(eventId, startedEvent);
        context.postEvent(startedEvent, actionId, timestamp);
        break;

      case FINISHED:
        Started started =
            Objects.requireNonNull(
                context.getStepStartedEvent(eventId),
                "Started step with event id: " + eventId + " is not found");
        context.postEvent(finished(started, 0), actionId, timestamp);
        break;

      case UNKNOWN:
      case UNRECOGNIZED:
      default:
        throw new IllegalStateException(
            "Step status: " + event.getStepStatus() + " is not supported!");
    }
  }
}
