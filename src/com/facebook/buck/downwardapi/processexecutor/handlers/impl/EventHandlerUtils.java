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

import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.downwardapi.processexecutor.handlers.EventHandler;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Utility methods related to {@link EventHandler} */
public class EventHandlerUtils {

  private EventHandlerUtils() {}

  /** Returns time value that equals to execution start time plus elapsed duration. */
  static Instant getTimestamp(DownwardApiExecutionContext context, Duration duration) {
    return context
        .getStartExecutionInstant()
        .plus(duration.getSeconds(), ChronoUnit.SECONDS)
        .plus(duration.getNanos(), ChronoUnit.NANOS);
  }

  /** Returns appropriate event handler for a given {@code eventType}. */
  @SuppressWarnings("unchecked")
  public static <T extends AbstractMessage> EventHandler<T> getStandardEventHandler(
      EventTypeMessage.EventType eventType) {
    switch (eventType) {
      case CONSOLE_EVENT:
        return (EventHandler<T>) ConsoleEventHandler.INSTANCE;

      case LOG_EVENT:
        return (EventHandler<T>) LogEventHandler.INSTANCE;

      case STEP_EVENT:
        return (EventHandler<T>) StepEventHandler.INSTANCE;

      case CHROME_TRACE_EVENT:
        return (EventHandler<T>) ChromeTraceEventHandler.INSTANCE;

      case EXTERNAL_EVENT:
        return (EventHandler<T>) ExternalEventHandler.INSTANCE;

      case RESULT_EVENT:
      case PIPELINE_FINISHED_EVENT:
        throw new IllegalStateException(
            "Client code is required to handle this event type: "
                + eventType
                + " with a custom event handler");

      case END_EVENT:
      case UNKNOWN:
      case UNRECOGNIZED:
      default:
        throw new IllegalStateException("Event type: " + eventType + " is not supported!");
    }
  }
}
