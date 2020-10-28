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

package com.facebook.buck.external.log;

import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.LogEvent;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.downwardapi.utils.DownwardApiUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * {@link Handler} that writes log events tos the given output stream. Does not manage the life
 * cycle of the output stream.
 */
public class ExternalLogHandler extends Handler {

  private final OutputStream outputStream;
  private volatile boolean closed = false;

  public ExternalLogHandler(OutputStream outputStream) {
    this.outputStream = outputStream;
  }

  @Override
  public void publish(LogRecord record) {
    if (closed) {
      throw new RuntimeException(
          String.format(
              "Attempting to write log event when handler already closed: [%s,%s,%s]",
              record.getLevel(), record.getMessage(), record.getLoggerName()));
    }
    if (!isLoggable(record)) {
      return;
    }
    LogEvent event =
        LogEvent.newBuilder()
            .setLogLevel(DownwardApiUtils.convertLogLevel(record.getLevel()))
            .setMessage(record.getMessage())
            .setLoggerName(record.getLoggerName())
            .build();
    try {
      DownwardProtocolType.BINARY
          .getDownwardProtocol()
          .write(createLogEventTypeMessage(), event, outputStream);
    } catch (IOException e) {
      throw new RuntimeException(
          String.format("Failed to write event to named pipe: %s", event), e);
    }
  }

  @Override
  public void flush() {
    if (closed) {
      throw new RuntimeException("Attempting to flush when log handler is already closed");
    }
    try {
      outputStream.flush();
    } catch (IOException e) {
      throw new RuntimeException("Failed to flush named pipe", e);
    }
  }

  @Override
  public void close() throws SecurityException {
    if (!closed) {
      flush();
      closed = true;
    }
  }

  private static EventTypeMessage createLogEventTypeMessage() {
    return EventTypeMessage.newBuilder().setEventType(EventTypeMessage.EventType.LOG_EVENT).build();
  }
}
