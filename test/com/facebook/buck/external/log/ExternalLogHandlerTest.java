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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.LogEvent;
import com.facebook.buck.downward.model.LogLevel;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.testutil.TemporaryPaths;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ExternalLogHandlerTest {

  private static final DownwardProtocol DOWNWARD_PROTOCOL =
      DownwardProtocolType.JSON.getDownwardProtocol();

  @Rule public final ExpectedException exception = ExpectedException.none();
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private File tempFile;
  private ExternalLogHandler testHandler;

  @Before
  public void setUp() throws IOException {
    tempFile = temporaryFolder.newFile("tmp_file").toFile();
    testHandler = new ExternalLogHandler(new FileOutputStream(tempFile), DOWNWARD_PROTOCOL);
  }

  @Test
  public void canWriteToOutputStream() throws IOException {
    String logMessage = "my logging message";
    String loggerName = "MyLoggerName";
    LogRecord record = new LogRecord(Level.WARNING, logMessage);
    record.setLoggerName(loggerName);

    testHandler.publish(record);

    InputStream inputStream = new FileInputStream(tempFile);

    EventTypeMessage.EventType actualEventType = DOWNWARD_PROTOCOL.readEventType(inputStream);
    LogEvent actualLogEvent = DOWNWARD_PROTOCOL.readEvent(inputStream, actualEventType);

    assertThat(actualEventType, equalTo(EventTypeMessage.EventType.LOG_EVENT));
    assertThat(actualLogEvent, equalTo(getExpectedLogEvent(LogLevel.WARN, logMessage, loggerName)));
  }

  @Test
  public void logRecordWithThrowable() throws IOException {
    String logMessage = "my logging message";
    String loggerName = "MyLoggerName";
    Throwable throwable =
        new Exception("Test exception message", new IllegalStateException("Test cause"));
    LogRecord record = new LogRecord(Level.WARNING, logMessage);
    record.setLoggerName(loggerName);
    record.setThrown(throwable);

    testHandler.publish(record);

    InputStream inputStream = new FileInputStream(tempFile);
    EventTypeMessage.EventType actualEventType = DOWNWARD_PROTOCOL.readEventType(inputStream);
    LogEvent actualLogEvent = DOWNWARD_PROTOCOL.readEvent(inputStream, actualEventType);

    assertThat(actualEventType, equalTo(EventTypeMessage.EventType.LOG_EVENT));
    assertThat(actualLogEvent.getLogLevel(), equalTo(LogLevel.WARN));
    assertThat(actualLogEvent.getLoggerName(), equalTo(loggerName));
    assertThat(actualLogEvent.getMessage(), containsString(logMessage));
    assertThat(
        actualLogEvent.getMessage(), containsString("java.lang.Exception: Test exception message"));
  }

  @Test
  public void throwsIfWriteAfterClose() {
    exception.expect(RuntimeException.class);
    exception.expectMessage(
        "Attempting to write log event when handler already closed: [WARNING,my logging message,MyLoggerName]");

    testHandler.close();
    String logMessage = "my logging message";
    String loggerName = "MyLoggerName";
    LogRecord record = new LogRecord(Level.WARNING, logMessage);
    record.setLoggerName(loggerName);

    testHandler.publish(record);
  }

  @Test
  public void throwsIfFlushAfterClose() {
    exception.expect(RuntimeException.class);
    exception.expectMessage("Attempting to flush when log handler is already closed");

    testHandler.close();
    testHandler.flush();
  }

  private static LogEvent getExpectedLogEvent(
      LogLevel logLevel, String message, String loggerName) {
    return LogEvent.newBuilder()
        .setLogLevel(logLevel)
        .setMessage(message)
        .setLoggerName(loggerName)
        .build();
  }
}
