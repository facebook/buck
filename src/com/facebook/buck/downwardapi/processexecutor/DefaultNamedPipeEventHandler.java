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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downwardapi.processexecutor.context.DownwardApiExecutionContext;
import com.facebook.buck.downwardapi.processexecutor.handlers.EventHandler;
import com.facebook.buck.downwardapi.processexecutor.handlers.impl.EventHandlerUtils;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.util.NamedPipeEventHandler;
import com.facebook.buck.util.NamedPipeEventHandlerFactory;
import com.google.protobuf.AbstractMessage;

/** Default implementation of {@link NamedPipeEventHandler} interface. */
public class DefaultNamedPipeEventHandler extends BaseNamedPipeEventHandler {

  private static final Logger LOG = Logger.get(DefaultNamedPipeEventHandler.class);

  public static final NamedPipeEventHandlerFactory FACTORY = DefaultNamedPipeEventHandler::new;

  DefaultNamedPipeEventHandler(NamedPipeReader namedPipe, DownwardApiExecutionContext context) {
    super(namedPipe, context);
  }

  @Override
  void processEvent(EventTypeMessage.EventType eventType, AbstractMessage event) {
    EventHandler<AbstractMessage> eventHandler =
        EventHandlerUtils.getStandardEventHandler(eventType);
    try {
      eventHandler.handleEvent(getContext(), event);
    } catch (Exception e) {
      LOG.error(e, "Cannot handle event: %s", event);
    }
  }
}
