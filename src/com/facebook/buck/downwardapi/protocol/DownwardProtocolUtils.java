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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.downward.model.ChromeTraceEvent;
import com.facebook.buck.downward.model.ConsoleEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.LogEvent;
import com.facebook.buck.downward.model.StepEvent;
import com.google.common.base.Preconditions;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Function;

/** Utility class for Downward Protocol API. */
class DownwardProtocolUtils {

  private static final String DELIMITER = System.lineSeparator();

  private DownwardProtocolUtils() {}

  /** Asserts that the given {@code message} matches the type from the given {@code eventType}. */
  static void checkMessageType(EventTypeMessage eventType, AbstractMessage message) {
    Class<? extends AbstractMessage> clazz = DownwardProtocolUtils.getExpectedClass(eventType);
    Preconditions.checkArgument(
        message.getClass().isAssignableFrom(clazz),
        "Expected %s, got %s",
        clazz.getName(),
        message.getClass().getName());
  }

  static void writeDelimiter(OutputStream outputStream) throws IOException {
    outputStream.write(DELIMITER.getBytes(UTF_8));
  }

  private static Class<? extends AbstractMessage> getExpectedClass(EventTypeMessage eventType) {
    switch (eventType.getEventType()) {
      case CONSOLE_EVENT:
        return ConsoleEvent.class;
      case LOG_EVENT:
        return LogEvent.class;
      case STEP_EVENT:
        return StepEvent.class;
      case CHROME_TRACE_EVENT:
        return ChromeTraceEvent.class;
      case UNKNOWN:
      case UNRECOGNIZED:
      default:
        throw new IllegalStateException("Unexpected value: " + eventType);
    }
  }

  static <T> T readFromStream(InputStream inputStream, Function<String, T> converter)
      throws IOException {
    int read;
    StringBuilder buffer = new StringBuilder();
    while ((read = inputStream.read()) != -1) {
      buffer.append((char) read);
      if (isDelimiter(buffer)) {
        buffer.setLength(buffer.length() - DELIMITER.length());
        return converter.apply(buffer.toString());
      }
    }
    throw new IllegalStateException("Can't read expected object!");
  }

  private static boolean isDelimiter(StringBuilder sb) {
    int length = sb.length();
    if (length < DELIMITER.length()) {
      return false;
    }
    for (int i = 0; i < DELIMITER.length(); i++) {
      if (DELIMITER.charAt(i) != sb.charAt(length - DELIMITER.length() + i)) {
        return false;
      }
    }
    return true;
  }
}
