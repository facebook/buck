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

package com.facebook.buck.downwardapi.processexecutor;

import static com.facebook.buck.downward.model.EventTypeMessage.EventType.CHROME_TRACE_EVENT;
import static com.facebook.buck.downward.model.EventTypeMessage.EventType.CONSOLE_EVENT;
import static com.facebook.buck.downward.model.EventTypeMessage.EventType.EXTERNAL_EVENT;
import static com.facebook.buck.downward.model.EventTypeMessage.EventType.LOG_EVENT;
import static com.facebook.buck.downward.model.EventTypeMessage.EventType.STEP_EVENT;

import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.downwardapi.processexecutor.handlers.EventHandler;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.AbstractMessage;
import java.util.concurrent.atomic.AtomicInteger;

class CustomNamedEventPipeHandler extends BaseNamedPipeEventHandler {

  private final ImmutableMap<EventTypeMessage.EventType, EventHandler<AbstractMessage>>
      eventHandlers;

  CustomNamedEventPipeHandler(
      NamedPipeReader namedPipe,
      DownwardApiExecutionContext downwardApiExecutionContext,
      AtomicInteger eventsReceivedByClientsHandlers) {
    super(namedPipe, downwardApiExecutionContext);
    this.eventHandlers =
        ImmutableMap.<EventTypeMessage.EventType, EventHandler<AbstractMessage>>builder()
            .put(LOG_EVENT, (context, event) -> eventsReceivedByClientsHandlers.getAndAdd(1))
            .put(CONSOLE_EVENT, (context, event) -> eventsReceivedByClientsHandlers.getAndAdd(2))
            .put(
                CHROME_TRACE_EVENT,
                (context, event) -> eventsReceivedByClientsHandlers.getAndAdd(10))
            .put(STEP_EVENT, (context, event) -> eventsReceivedByClientsHandlers.getAndAdd(100))
            .put(EXTERNAL_EVENT, (context, event) -> eventsReceivedByClientsHandlers.getAndAdd(500))
            .build();
  }

  @Override
  protected void processEvent(EventTypeMessage.EventType eventType, AbstractMessage event) {
    EventHandler<AbstractMessage> handler = eventHandlers.get(eventType);
    handler.handleEvent(getContext(), event);
  }
}
