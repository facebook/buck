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

package com.facebook.buck.downwardapi.protocol;

import com.facebook.buck.downward.model.ChromeTraceEvent;
import com.facebook.buck.downward.model.ConsoleEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.LogEvent;
import com.facebook.buck.downward.model.StepEvent;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Binary implementation of Downward API Protocol. */
enum BinaryDownwardProtocol implements DownwardProtocol {
  INSTANCE;

  private static final String PROTOCOL_NAME = "binary";

  @Override
  public void write(EventTypeMessage eventType, AbstractMessage message, OutputStream outputStream)
      throws IOException {
    DownwardProtocolUtils.checkMessageType(eventType, message);
    synchronized (this) {
      eventType.writeDelimitedTo(outputStream);
      message.writeDelimitedTo(outputStream);
    }
  }

  @Override
  public EventTypeMessage.EventType readEventType(InputStream inputStream) throws IOException {
    return EventTypeMessage.parseDelimitedFrom(inputStream).getEventType();
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T extends AbstractMessage> T readEvent(
      InputStream inputStream, EventTypeMessage.EventType eventType) throws IOException {
    return (T) parseMessage(inputStream, eventType);
  }

  @Override
  public String getProtocolName() {
    return PROTOCOL_NAME;
  }

  private AbstractMessage parseMessage(
      InputStream inputStream, EventTypeMessage.EventType eventType) throws IOException {
    switch (eventType) {
      case CONSOLE_EVENT:
        return ConsoleEvent.parseDelimitedFrom(inputStream);
      case LOG_EVENT:
        return LogEvent.parseDelimitedFrom(inputStream);
      case STEP_EVENT:
        return StepEvent.parseDelimitedFrom(inputStream);
      case CHROME_TRACE_EVENT:
        return ChromeTraceEvent.parseDelimitedFrom(inputStream);

      case UNKNOWN:
      case UNRECOGNIZED:
      default:
        throw new IllegalStateException("Unexpected value: " + eventType);
    }
  }
}
