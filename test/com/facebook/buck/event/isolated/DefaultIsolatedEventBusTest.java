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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ChromeTraceEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.LogLevel;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.downwardapi.testutil.ChromeTraceEventMatcher;
import com.facebook.buck.downwardapi.testutil.StepEventMatcher;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StepEvent;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestLogSink;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Duration;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultIsolatedEventBusTest {

  private static final Logger LOG = Logger.get(DefaultIsolatedEventBusTest.class);

  private static final DownwardProtocol DOWNWARD_PROTOCOL =
      DownwardProtocolType.BINARY.getDownwardProtocol();

  private static final long NOW_MILLIS =
      Instant.parse("2020-12-15T12:13:14.123456789Z").toEpochMilli();
  private static final int CLOCK_SHIFT_IN_SECONDS = 4242;

  private static final String TEST_ACTION_ID = "test-action-id";

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  @Rule public final ExpectedException exception = ExpectedException.none();

  private OutputStream outputStream;
  private InputStream inputStream;
  private DefaultIsolatedEventBus testEventBus;

  @Rule public TestLogSink logSink = new TestLogSink(DefaultIsolatedEventBus.class.getName());

  @Before
  public void setUp() throws Exception {
    File tempFile = temporaryFolder.newFile("tmp_file").toFile();
    outputStream = new FileOutputStream(tempFile);
    inputStream = new FileInputStream(tempFile);
    testEventBus =
        new DefaultIsolatedEventBus(
            BuckEventBusForTests.BUILD_ID_FOR_TEST,
            outputStream,
            FakeClock.of(NOW_MILLIS + TimeUnit.SECONDS.toMillis(CLOCK_SHIFT_IN_SECONDS), 0),
            NOW_MILLIS,
            DownwardProtocolType.BINARY.getDownwardProtocol());
  }

  @After
  public void tearDown() throws Exception {
    outputStream.close();
    inputStream.close();
    testEventBus.close();
  }

  @Test
  public void consoleEventCanBeWrittenToOutputStream() throws Exception {
    ConsoleEvent consoleEvent = ConsoleEvent.create(Level.SEVERE, "test_message");
    com.facebook.buck.downward.model.ConsoleEvent expectedConsoleEvent =
        com.facebook.buck.downward.model.ConsoleEvent.newBuilder()
            .setLogLevel(LogLevel.ERROR)
            .setMessage("test_message")
            .build();

    testEventBus.post(consoleEvent);
    EventTypeMessage.EventType actualEventType = DOWNWARD_PROTOCOL.readEventType(inputStream);
    com.facebook.buck.downward.model.ConsoleEvent actualConsoleEvent =
        DOWNWARD_PROTOCOL.readEvent(inputStream, actualEventType);

    assertThat(actualEventType, equalTo(EventTypeMessage.EventType.CONSOLE_EVENT));
    assertThat(actualConsoleEvent, equalTo(expectedConsoleEvent));
  }

  @Test
  public void stepEventCanBeWrittenToOutputStream() throws Exception {
    StepEvent stepEvent = StepEvent.started("short_name", "my_description");
    int secondsElapsedTillEventOccurred = 123;
    com.facebook.buck.downward.model.StepEvent expectedStepEvent =
        com.facebook.buck.downward.model.StepEvent.newBuilder()
            .setDescription("my_description")
            .setStepType("short_name")
            .setStepStatus(com.facebook.buck.downward.model.StepEvent.StepStatus.STARTED)
            .setDuration(Duration.newBuilder().setSeconds(secondsElapsedTillEventOccurred).build())
            .build();

    testEventBus.post(
        stepEvent,
        TEST_ACTION_ID,
        Instant.ofEpochMilli(
            NOW_MILLIS + TimeUnit.SECONDS.toMillis(secondsElapsedTillEventOccurred)),
        Thread.currentThread().getId());
    EventTypeMessage.EventType actualEventType = DOWNWARD_PROTOCOL.readEventType(inputStream);
    com.facebook.buck.downward.model.StepEvent actualStepEvent =
        DOWNWARD_PROTOCOL.readEvent(inputStream, actualEventType);

    assertThat(actualEventType, equalTo(EventTypeMessage.EventType.STEP_EVENT));
    assertThat(actualStepEvent, StepEventMatcher.equalsStepEvent(expectedStepEvent));
  }

  @Test
  public void perfEventCanBeWrittenToOutputStream() throws Exception {
    // Creating a scope sends a start event
    SimplePerfEvent.Scope scope =
        SimplePerfEvent.scopeWithActionId(
            testEventBus,
            TEST_ACTION_ID,
            SimplePerfEvent.PerfEventTitle.of("my_event"),
            ImmutableMap.of("my_path_key", Paths.get("my_path_value")));
    // Closing the scope sends a finish event
    scope.close();

    int secondsElapsedTillEventOccurred = CLOCK_SHIFT_IN_SECONDS;

    ChromeTraceEvent expectedStartEvent =
        ChromeTraceEvent.newBuilder()
            .setEventId(123)
            .setStatus(ChromeTraceEvent.ChromeTraceEventStatus.BEGIN)
            .setTitle("my_event")
            .setCategory("buck")
            .putData("my_path_key", Paths.get("my_path_value").toString())
            .setDuration(Duration.newBuilder().setSeconds(secondsElapsedTillEventOccurred).build())
            .build();
    ChromeTraceEvent expectedFinishEvent =
        ChromeTraceEvent.newBuilder()
            .setEventId(123)
            .setStatus(ChromeTraceEvent.ChromeTraceEventStatus.END)
            .setTitle("my_event")
            .setCategory("buck")
            .setDuration(Duration.newBuilder().setSeconds(secondsElapsedTillEventOccurred).build())
            .build();

    EventTypeMessage.EventType actualStartEventType = DOWNWARD_PROTOCOL.readEventType(inputStream);
    ChromeTraceEvent actualStartEvent =
        DOWNWARD_PROTOCOL.readEvent(inputStream, actualStartEventType);
    assertThat(actualStartEventType, equalTo(EventTypeMessage.EventType.CHROME_TRACE_EVENT));
    assertThat(actualStartEvent, ChromeTraceEventMatcher.equalsTraceEvent(expectedStartEvent));

    EventTypeMessage.EventType actualFinishEventType = DOWNWARD_PROTOCOL.readEventType(inputStream);
    ChromeTraceEvent actualFinishEvent =
        DOWNWARD_PROTOCOL.readEvent(inputStream, actualFinishEventType);
    assertThat(actualFinishEventType, equalTo(EventTypeMessage.EventType.CHROME_TRACE_EVENT));
    assertThat(actualFinishEvent, ChromeTraceEventMatcher.equalsTraceEvent(expectedFinishEvent));

    assertThat(actualStartEvent.getEventId(), equalTo(actualFinishEvent.getEventId()));
  }

  @Test(timeout = 1_000)
  public void waitTillAllEventsProcessed() throws InterruptedException {
    int batch1Count = 3;
    int batch2Count = 4;
    AtomicInteger processedCount = new AtomicInteger(0);
    CountDownLatch taskStarted = new CountDownLatch(batch1Count + batch2Count);

    CountDownLatch batch1Blocked = new CountDownLatch(1);
    CountDownLatch batch2Blocked = new CountDownLatch(1);

    testEventBus =
        new DefaultIsolatedEventBus(
            BuckEventBusForTests.BUILD_ID_FOR_TEST,
            outputStream,
            FakeClock.of(NOW_MILLIS, 0),
            NOW_MILLIS,
            DownwardProtocolType.BINARY.getDownwardProtocol(),
            true) {

          @Override
          void writeIntoStream(EventTypeMessage eventType, AbstractMessage payload) {
            taskStarted.countDown();

            // the first batch
            boolean isFirstBatch = processedCount.incrementAndGet() <= batch1Count;
            if (isFirstBatch) {
              try {
                batch1Blocked.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("Wait has been interrupted");
              }
            } else { // the second batch
              try {
                batch2Blocked.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("Wait has been interrupted");
              }
            }
          }
        };

    ScheduledExecutorService executorService =
        Executors.newScheduledThreadPool(batch1Count + batch2Count + 3);
    try {
      // start the first batch at T0
      for (int i = 0; i < batch1Count; i++) {
        int taskId = i;
        executorService.submit(
            () -> testEventBus.post(ConsoleEvent.create(Level.INFO, "message_bunch_1_" + taskId)));
      }

      // start the second batch at T1 = 100ms
      int startTheSecondBatchTime = 100;
      for (int i = 0; i < batch2Count; i++) {
        int taskId = i;
        executorService.schedule(
            () -> testEventBus.post(ConsoleEvent.create(Level.INFO, "message_bunch_2_ " + taskId)),
            startTheSecondBatchTime,
            TimeUnit.MILLISECONDS);
      }

      // unblock the first batch at T2 = 300ms
      int unlockTheFirstBatchTime = 300;
      executorService.schedule(
          () -> batch1Blocked.countDown(), unlockTheFirstBatchTime, TimeUnit.MILLISECONDS);

      // unblock the second batch at T3 = 700ms
      int unlockTheSecondBatchTime = 700;
      executorService.schedule(
          () -> batch2Blocked.countDown(), unlockTheSecondBatchTime, TimeUnit.MILLISECONDS);

      // wait till all tasks started. Should be about T1=100ms as the second batch starts with delay
      taskStarted.await();

      // all tasks started and nothing has been processed.
      assertThat(testEventBus.getUnprocessedEventsCount(), equalTo(batch1Count + batch2Count));
      // at 500ms we should have the first batch processed as it has been started at T0 and
      // unblocked at T2 = 300ms
      executorService.schedule(
          () -> assertThat(testEventBus.getUnprocessedEventsCount(), equalTo(batch2Count)),
          500,
          TimeUnit.MILLISECONDS);

      // should wait about T3-T1=600ms for the both batch to complete.
      testEventBus.waitTillAllEventsProcessed();

      // all tasks have been processed.
      assertThat(testEventBus.getUnprocessedEventsCount(), equalTo(0));

      Iterator<LogRecord> iterator = logSink.getRecords().iterator();
      LogRecord logRecord = Iterators.getOnlyElement(iterator);
      String message = logRecord.getMessage();
      Matcher matcher = Pattern.compile("Waited for (\\d+) ms").matcher(message);
      assertThat(matcher.find(), is(true));
      double msWaited = Double.parseDouble(matcher.group(1));

      int expectedWaitTime = unlockTheSecondBatchTime - startTheSecondBatchTime;
      // compare with 5% tolerance
      assertThat(msWaited, closeTo(expectedWaitTime, expectedWaitTime * 0.05));
    } finally {
      executorService.shutdownNow();
    }
  }
}
