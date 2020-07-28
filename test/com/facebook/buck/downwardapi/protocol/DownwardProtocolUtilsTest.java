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

import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.LogEvent;
import com.facebook.buck.downward.model.LogLevel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DownwardProtocolUtilsTest {

  @Rule public final ExpectedException exception = ExpectedException.none();

  @Test
  public void failsIfPayloadDoesNotMatchEventType() {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage(
        "Expected com.facebook.buck.downward.model.ConsoleEvent, got com.facebook.buck.downward.model.LogEvent");
    DownwardProtocolUtils.checkMessageType(
        createEventTypeMessage(EventTypeMessage.EventType.CONSOLE_EVENT),
        LogEvent.newBuilder()
            .setMessage("msg")
            .setLoggerName("my_logger")
            .setLogLevel(LogLevel.INFO)
            .build());
  }

  @Test
  public void succeedsIfPayloadDoesNotMatchEventType() {
    DownwardProtocolUtils.checkMessageType(
        createEventTypeMessage(EventTypeMessage.EventType.LOG_EVENT),
        LogEvent.newBuilder()
            .setMessage("msg")
            .setLoggerName("my_logger")
            .setLogLevel(LogLevel.INFO)
            .build());
  }

  private static EventTypeMessage createEventTypeMessage(EventTypeMessage.EventType type) {
    return EventTypeMessage.newBuilder().setEventType(type).build();
  }
}
