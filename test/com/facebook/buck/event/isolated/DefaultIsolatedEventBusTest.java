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

package com.facebook.buck.event.isolated;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.LogLevel;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultIsolatedEventBusTest {

  private static final int TIMEOUT_MILLIS = 500;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  @Rule public final ExpectedException exception = ExpectedException.none();

  private ListeningExecutorService executorService;
  private OutputStream outputStream;
  private InputStream inputStream;
  private DefaultIsolatedEventBus testEventBus;

  @Before
  public void setUp() throws Exception {
    File tempFile = temporaryFolder.newFile("tmp_file").toFile();
    outputStream = new FileOutputStream(tempFile);
    inputStream = new FileInputStream(tempFile);
    executorService = MoreExecutors.newDirectExecutorService();
    testEventBus =
        new DefaultIsolatedEventBus(
            BuckEventBusForTests.BUILD_ID_FOR_TEST,
            outputStream,
            FakeClock.doNotCare(),
            executorService,
            TIMEOUT_MILLIS);
  }

  @After
  public void tearDown() throws Exception {
    outputStream.close();
    inputStream.close();
    testEventBus.close();
  }

  @Test
  public void postWritesToOutputStream() throws Exception {
    ConsoleEvent consoleEvent = ConsoleEvent.create(Level.SEVERE, "test_message");
    com.facebook.buck.downward.model.ConsoleEvent expectedConsoleEvent =
        com.facebook.buck.downward.model.ConsoleEvent.newBuilder()
            .setLogLevel(LogLevel.ERROR)
            .setMessage("test_message")
            .build();

    testEventBus.post(consoleEvent);
    EventTypeMessage.EventType actualEventType =
        DownwardProtocolType.BINARY.getDownwardProtocol().readEventType(inputStream);
    com.facebook.buck.downward.model.ConsoleEvent actualConsoleEvent =
        DownwardProtocolType.BINARY.getDownwardProtocol().readEvent(inputStream, actualEventType);

    assertThat(actualEventType, equalTo(EventTypeMessage.EventType.CONSOLE_EVENT));
    assertThat(actualConsoleEvent, equalTo(expectedConsoleEvent));
  }

  @Test
  public void failsIfReceiveUnsupportedBuckEvent() {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage(
        "Unexpected event type: com.facebook.buck.event.isolated.DefaultIsolatedEventBusTest$FakeBuckEvent");
    List<LogRecord> logRecords = new ArrayList<>();
    LogManager.getLogManager()
        .getLogger(DefaultIsolatedEventBus.class.getSimpleName())
        .addHandler(
            new Handler() {
              @Override
              public void publish(LogRecord record) {
                logRecords.add(record);
              }

              @Override
              public void flush() {}

              @Override
              public void close() throws SecurityException {}
            });

    testEventBus.post(new FakeBuckEvent(EventKey.unique()));
  }

  @Test
  public void closeShutsDownExecutor() throws Exception {
    assertFalse(executorService.isShutdown());
    testEventBus.close();
    assertTrue(executorService.isShutdown());
  }

  private static class FakeBuckEvent extends AbstractBuckEvent {

    protected FakeBuckEvent(EventKey eventKey) {
      super(eventKey);
    }

    @Override
    protected String getValueString() {
      return "fake_value_string";
    }

    @Override
    public String getEventName() {
      return "fake_event";
    }
  }
}
