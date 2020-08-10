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

package com.facebook.buck.downwardapi.processexecutor.handlers;

import static com.facebook.buck.event.StepEvent.finished;
import static com.facebook.buck.event.StepEvent.started;

import com.facebook.buck.downward.model.StepEvent;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiExecutionContext;
import com.facebook.buck.event.StepEvent.Started;
import com.facebook.buck.step.StepExecutionResults;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Downward API event handler for {@code StepEvent} */
enum StepEventHandler implements EventHandler<StepEvent> {
  INSTANCE;

  @Override
  public void handleEvent(DownwardApiExecutionContext context, StepEvent event) {
    Map<Integer, Started> stepStartedEvents = context.getStepStartedEvents();
    Instant timestamp = EventHandler.getTimestamp(context, event.getDuration());
    int eventId = event.getEventId();

    switch (event.getStepStatus()) {
      case STARTED:
        Started startedEvent =
            started(event.getStepType(), event.getDescription(), UUID.randomUUID());
        stepStartedEvents.put(eventId, startedEvent);
        context.postEvent(startedEvent, timestamp);
        break;

      case FINISHED:
        Started started =
            Objects.requireNonNull(
                stepStartedEvents.remove(eventId),
                "Started step with event id: " + eventId + " is not found");
        context.postEvent(finished(started, StepExecutionResults.SUCCESS_EXIT_CODE), timestamp);
        break;

      case UNKNOWN:
      case UNRECOGNIZED:
      default:
        throw new IllegalStateException(
            "Step status: " + event.getStepStatus() + " is not supported!");
    }
  }
}
