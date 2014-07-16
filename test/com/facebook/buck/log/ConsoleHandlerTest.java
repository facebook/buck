/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.log;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.FakeOutputStream;

import java.io.IOException;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.Test;

/**
 * Unit tests for {@link ConsoleHandler}.
 */
public class ConsoleHandlerTest {
  // We use a custom formatter so the test doesn't depend on locale, clock, or timezone.
  private static class MessageOnlyFormatter extends Formatter {
    @Override
    public String format(LogRecord record) {
      return record.getMessage();
    }
  };

  @Test
  public void consoleHandlerDoesNotWriteBelowLevelToStream() {
    FakeOutputStream outputStream = new FakeOutputStream();
    ConsoleHandler handler = new ConsoleHandler(
        outputStream, new MessageOnlyFormatter(), Level.INFO);
    publishAndFlush(handler, new LogRecord(Level.FINE, "Shh.."));
    assertThat(outputStream.size(), equalTo(0));
  }

  @Test
  public void consoleHandlerWritesAtLevelToStream() throws IOException {
    FakeOutputStream outputStream = new FakeOutputStream();
    ConsoleHandler handler = new ConsoleHandler(
        outputStream, new MessageOnlyFormatter(), Level.INFO);
    publishAndFlush(handler, new LogRecord(Level.INFO, "Hello"));
    assertThat(outputStream.toString("UTF-8"), equalTo("Hello"));
  }

  @Test
  public void consoleHandlerCanChangeOutputStreamWithoutClosing() throws IOException {
    FakeOutputStream outputStream1 = new FakeOutputStream();
    FakeOutputStream outputStream2 = new FakeOutputStream();
    ConsoleHandler handler = new ConsoleHandler(
        outputStream1, new MessageOnlyFormatter(), Level.INFO);
    publishAndFlush(handler, new LogRecord(Level.INFO, "Stream 1"));
    assertThat(outputStream1.toString("UTF-8"), equalTo("Stream 1"));

    handler.setOutputStream(outputStream2);
    assertThat(outputStream1.isClosed(), equalTo(false));

    publishAndFlush(handler, new LogRecord(Level.INFO, "Stream 2"));
    assertThat(outputStream1.toString("UTF-8"), equalTo("Stream 1"));
    assertThat(outputStream2.toString("UTF-8"), equalTo("Stream 2"));

    handler.setOutputStream(outputStream1);
    assertThat(outputStream2.isClosed(), equalTo(false));

    publishAndFlush(handler, new LogRecord(Level.INFO, " - DONE"));
    assertThat(outputStream1.toString("UTF-8"), equalTo("Stream 1 - DONE"));
    assertThat(outputStream2.toString("UTF-8"), equalTo("Stream 2"));
  }

  private static void publishAndFlush(Handler handler, LogRecord logRecord) {
    handler.publish(logRecord);
    handler.flush();
  }
}
