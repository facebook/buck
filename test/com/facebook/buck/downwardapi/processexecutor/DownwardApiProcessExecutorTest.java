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

import static com.facebook.buck.downward.model.StepEvent.StepStatus.FINISHED;
import static com.facebook.buck.downward.model.StepEvent.StepStatus.STARTED;
import static com.facebook.buck.downwardapi.processexecutor.TestUtils.TEST_LOGGER_NAME;
import static com.facebook.buck.downwardapi.processexecutor.TestUtils.chromeTraceEvent;
import static com.facebook.buck.downwardapi.processexecutor.TestUtils.consoleEvent;
import static com.facebook.buck.downwardapi.processexecutor.TestUtils.externalEvent;
import static com.facebook.buck.downwardapi.processexecutor.TestUtils.getDownwardApiProcessExecutor;
import static com.facebook.buck.downwardapi.processexecutor.TestUtils.getProcessExecutorParams;
import static com.facebook.buck.downwardapi.processexecutor.TestUtils.launchAndExecute;
import static com.facebook.buck.downwardapi.processexecutor.TestUtils.logEvent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ChromeTraceEvent.ChromeTraceEventStatus;
import com.facebook.buck.downwardapi.namedpipes.DownwardPOSIXNamedPipeFactory;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.downwardapi.protocol.JsonDownwardProtocol;
import com.facebook.buck.downwardapi.testutil.LogRecordMatcher;
import com.facebook.buck.downwardapi.testutil.TestWindowsHandleFactory;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.external.events.StepEventExternalInterface;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeFactory;
import com.facebook.buck.io.namedpipes.windows.handle.WindowsHandleFactory;
import com.facebook.buck.testutil.ExecutorServiceUtils;
import com.facebook.buck.testutil.TestLogSink;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;

public class DownwardApiProcessExecutorTest {

  private static final Logger LOG = Logger.get(DownwardApiProcessExecutorTest.class);

  private static final Logger EVENT_HANDLER_LOGGER = BaseNamedPipeEventHandler.LOGGER;
  private static Level eventHandlerLoggerInitialLevel;

  private static WindowsHandleFactory initialWindowsHandleFactory;
  private static final TestWindowsHandleFactory TEST_WINDOWS_HANDLE_FACTORY =
      new TestWindowsHandleFactory();

  @BeforeClass
  public static void beforeClass() {
    // store the initial log level in the variable
    eventHandlerLoggerInitialLevel = EVENT_HANDLER_LOGGER.getLevel();
    // add ability to check all level of logs from class under the test.
    EVENT_HANDLER_LOGGER.setLevel(Level.ALL);

    // override WindowsHandleFactory with a test one
    initialWindowsHandleFactory = WindowsNamedPipeFactory.windowsHandleFactory;
    WindowsNamedPipeFactory.windowsHandleFactory = TEST_WINDOWS_HANDLE_FACTORY;
  }

  @AfterClass
  public static void afterClass() {
    // set the initial log level back
    EVENT_HANDLER_LOGGER.setLevel(eventHandlerLoggerInitialLevel);
    WindowsNamedPipeFactory.windowsHandleFactory = initialWindowsHandleFactory;
  }

  @After
  public void afterTest() {
    if (Platform.detect() == Platform.WINDOWS) {
      TEST_WINDOWS_HANDLE_FACTORY.verifyAllCreatedHandlesClosed();
    }
  }

  @Rule public Timeout globalTestTimeout = Timeout.seconds(10);
  @Rule public TestName testName = new TestName();

  @Rule public TestLogSink testToolLogSink = new TestLogSink(TEST_LOGGER_NAME);

  @Rule
  public TestLogSink executorLogSink = new TestLogSink(BaseNamedPipeEventHandler.class.getName());

  @Rule
  public TestLogSink jsonProtocolLogSing = new TestLogSink(JsonDownwardProtocol.class.getName());

  private NamedPipeReader namedPipeReader;
  private BuckEventBus buckEventBus;
  private ProcessExecutorParams params;
  private Clock clock;

  @Before
  public void setUp() throws Exception {
    namedPipeReader =
        NamedPipeFactory.getFactory(
                DownwardPOSIXNamedPipeFactory.INSTANCE, WindowsNamedPipeFactory.INSTANCE)
            .createAsReader();
    Instant instant = Instant.now();
    clock = new SettableFakeClock(instant.toEpochMilli(), instant.getNano());
    buckEventBus = BuckEventBusForTests.newInstance(clock);
    params = getProcessExecutorParams(namedPipeReader.getName(), buckEventBus);
  }

  @Test
  public void downwardApiWithNoWriters() throws IOException, InterruptedException {
    // do nothing
    FakeProcess fakeProcess = new FakeProcess(Optional.of(Optional::empty));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);
    verifyExecutionResult(result);
  }

  @Test
  public void downwardApi() throws Exception {
    TestListener listener = new TestListener();
    long epochMilli = clock.currentTimeMillis();
    buckEventBus.register(listener);

    ProcessExecutorParams params =
        getProcessExecutorParams(namedPipeReader.getName(), buckEventBus);

    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try {
                    writeIntoNamedPipeProcess(namedPipeReader.getName());
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);

    AtomicReference<DownwardApiExecutionResult> resultReference = new AtomicReference<>();
    AtomicLong threadIdReference = new AtomicLong();

    // Run in thread that is different from main thread's. Would create a mapping between thread
    // id and action id.
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    try {
      Future<Void> f =
          executorService.submit(
              () -> {
                threadIdReference.set(Thread.currentThread().getId());
                resultReference.set(launchAndExecute(processExecutor));
                return null;
              });
      f.get(1, TimeUnit.SECONDS);
    } finally {
      executorService.shutdownNow();
    }

    DownwardApiExecutionResult executionResult = resultReference.get();
    verifyExecutionResult(executionResult);

    waitTillEventsProcessed();
    Map<Integer, BuckEvent> events = listener.getEvents();
    assertEquals(6, events.size());

    for (BuckEvent buckEvent : events.values()) {
      if (!(buckEvent instanceof com.facebook.buck.event.StepEvent
          || buckEvent instanceof SimplePerfEvent)) {
        // we care about thread id only for step and chrome trace events.
        continue;
      }
      assertEquals(
          "Thread id for events has to be equals to thread id of the invoking thread. Failed event: "
              + buckEvent,
          threadIdReference.get(),
          buckEvent.getThreadId());
    }

    // step start event
    verifyStepEvent(
        epochMilli,
        events.get(0),
        StepEventExternalInterface.STEP_STARTED,
        "crazy_stuff",
        "launched_process step started",
        50);

    // console event
    verifyConsoleEvent(events.get(1));

    // chrome trace start event
    verifyChromeTraceEvent(
        epochMilli,
        events.get(2),
        "category_1",
        SimplePerfEvent.Type.STARTED,
        ImmutableMap.of("key1", "value1", "key2", "value2"),
        100);

    // step finished event
    verifyStepEvent(
        epochMilli,
        events.get(3),
        StepEventExternalInterface.STEP_FINISHED,
        "crazy_stuff",
        // the same as in started event
        "launched_process step started",
        55);

    // chrome trace finished event
    verifyChromeTraceEvent(
        epochMilli,
        events.get(4),
        "category_1",
        SimplePerfEvent.Type.FINISHED,
        ImmutableMap.of("key3", "value3"),
        150);

    // log event
    verifyLogEvent();

    // verify error event
    verifyExternalEvent(events.get(5));

    checkRecords(
        "Did not find debug log message about processing on a handler thread pool",
        executorLogSink,
        Level.FINER,
        logRecord -> {
          String message = logRecord.getMessage();
          return message.contains("Processing event of type")
              && message.contains(
                  "in the thread: " + DownwardApiProcessExecutor.HANDLER_THREAD_POOL_NAME);
        });

    buckEventBus.unregister(listener);
  }

  private void waitTillEventsProcessed() throws InterruptedException {
    ExecutorServiceUtils.waitTillAllTasksCompleted(
        (ThreadPoolExecutor) DownwardApiProcessExecutor.HANDLER_THREAD_POOL);
  }

  @Test
  public void invalidProtocol() throws IOException, InterruptedException {
    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try (NamedPipeWriter writer =
                          NamedPipeFactory.getFactory()
                              .connectAsWriter(Paths.get(namedPipeReader.getName()));
                      OutputStream outputStream = writer.getOutputStream()) {
                    // "u" is not a valid protocol
                    outputStream.write("u".getBytes(StandardCharsets.UTF_8));
                    outputStream.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
                    outputStream.write("blah".getBytes(StandardCharsets.UTF_8));
                    outputStream.write("blah-blah".getBytes(StandardCharsets.UTF_8));
                    outputStream.write("blah-blah-blah".getBytes(StandardCharsets.UTF_8));
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);
    verifyExecutionResult(result);

    checkRecords(
        "Did not find log message about unexpected protocol",
        executorLogSink,
        Level.SEVERE,
        logRecord -> {
          String message = logRecord.getMessage();
          if (!message.contains("Received invalid downward protocol message")) {
            return false;
          }
          return logRecord.getThrown().getMessage().contains("Invalid protocol type: u");
        });

    checkRecords(
        "Did not find log message about read and drop data from the input stream",
        executorLogSink,
        Level.INFO,
        logRecord -> {
          String message = logRecord.getMessage();
          return message.startsWith("Read and drop ")
              && message.endsWith("bytes from named pipe: " + namedPipeReader.getName());
        });
  }

  private void checkRecords(
      String errorMessage,
      TestLogSink testLogSink,
      Level level,
      Function<LogRecord, Boolean> predicate)
      throws InterruptedException {
    waitTillEventsProcessed();
    // give log events some time to arrive
    TimeUnit.MILLISECONDS.sleep(100);
    Predicate<LogRecord> nonNullPredicate = ((Predicate<LogRecord>) Objects::isNull).negate();
    assertThat(
        errorMessage,
        testLogSink.getRecords().stream()
            .filter(nonNullPredicate)
            .filter(logRecord -> logRecord.getLevel().equals(level))
            .collect(Collectors.toList()),
        hasItem(
            new LogRecordMatcher(level) {
              @Override
              public boolean marchesLogRecord(LogRecord logRecord) {
                return predicate.apply(logRecord);
              }
            }));
  }

  @Test
  public void coupleOfValidEventsAndThenMalformedData() throws IOException, InterruptedException {
    int fakeBytesToWrite = 100_000;
    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try (NamedPipeWriter writer =
                          NamedPipeFactory.getFactory()
                              .connectAsWriter(Paths.get(namedPipeReader.getName()));
                      OutputStream outputStream = writer.getOutputStream()) {
                    for (String message : getMessages()) {
                      outputStream.write(message.getBytes(StandardCharsets.UTF_8));
                    }

                    // invalid symbol
                    outputStream.write(
                        String.format("_%s", System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8));
                    // fake bytes
                    for (int i = 0; i < fakeBytesToWrite; i++) {
                      outputStream.write(i % 42);
                    }
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);
    verifyExecutionResult(result);

    checkRecords(
        "Did not find log message about read and drop " + fakeBytesToWrite + " fake bytes",
        executorLogSink,
        Level.INFO,
        logRecord -> {
          String message = logRecord.getMessage();
          return message.equals(
                  "Read and drop "
                      + fakeBytesToWrite
                      + " total bytes from named pipe: "
                      + namedPipeReader.getName())
              && message.endsWith("bytes from named pipe: " + namedPipeReader.getName());
        });
  }

  @Test
  public void jsonProtocolWithInvalidLengthOfMessage() throws IOException, InterruptedException {
    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try (NamedPipeWriter writer =
                          NamedPipeFactory.getFactory()
                              .connectAsWriter(Paths.get(namedPipeReader.getName()));
                      OutputStream outputStream = writer.getOutputStream()) {

                    // json protocol
                    outputStream.write(
                        String.format(
                                "%s%s",
                                DownwardProtocolType.JSON.getProtocolId(), System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8));

                    // invalid length
                    outputStream.write(
                        String.format("%s%s", "12X89", System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8));
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);
    verifyExecutionResult(result);

    checkRecords(
        "Did not find log message about wrong json message length",
        executorLogSink,
        Level.SEVERE,
        logRecord -> {
          String message = logRecord.getMessage();
          if (!message.contains("Received invalid downward protocol message")) {
            return false;
          }
          return logRecord
              .getThrown()
              .getMessage()
              .equals(
                  "Exception parsing integer number representing json message length: For input string: \"12X89\"");
        });
  }

  @Test
  public void multipleJsonProtocolClients() throws IOException, InterruptedException {
    TestListener listener = new TestListener();
    buckEventBus.register(listener);

    ImmutableList<String> messages = getMessages();
    int messageSize = messages.size();
    assertThat(messageSize, equalTo(9));

    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try (NamedPipeWriter writer =
                          NamedPipeFactory.getFactory()
                              .connectAsWriter(Paths.get(namedPipeReader.getName()));
                      OutputStream outputStream = writer.getOutputStream()) {
                    String protocolMessage = null;

                    for (int i = 0; i < messageSize; i++) {
                      String message = messages.get(i);
                      if (i == 0) {
                        protocolMessage = message;
                        // write protocol message twice
                        outputStream.write(protocolMessage.getBytes(StandardCharsets.UTF_8));
                      }
                      outputStream.write(message.getBytes(StandardCharsets.UTF_8));

                      if (i % 2 == 0) {
                        outputStream.write(protocolMessage.getBytes(StandardCharsets.UTF_8));
                      }
                    }

                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);
    DownwardApiExecutionResult executionResult = launchAndExecute(processExecutor);
    verifyExecutionResult(executionResult);

    waitTillEventsProcessed();
    Map<Integer, BuckEvent> events = listener.getEvents();
    assertEquals(6, events.size());

    checkRecords(
        "Did not find log message about another tool",
        jsonProtocolLogSing,
        Level.INFO,
        logRecord ->
            logRecord.getMessage().equals("Another tool wants to establish protocol over json"));
  }

  @Test
  public void multipleNestedToolsWithJsonProtocol() throws IOException, InterruptedException {
    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try (NamedPipeWriter writer =
                          NamedPipeFactory.getFactory()
                              .connectAsWriter(Paths.get(namedPipeReader.getName()));
                      OutputStream outputStream = writer.getOutputStream()) {
                    for (int i = 0; i < 100; i++) {
                      outputStream.write(
                          String.format(
                                  "%s%s",
                                  DownwardProtocolType.JSON.getProtocolId(), System.lineSeparator())
                              .getBytes(StandardCharsets.UTF_8));
                    }

                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);
    DownwardApiExecutionResult executionResult = launchAndExecute(processExecutor);
    verifyExecutionResult(executionResult);

    checkRecords(
        "Did not find log message about another tool",
        jsonProtocolLogSing,
        Level.INFO,
        logRecord ->
            logRecord.getMessage().equals("Another tool wants to establish protocol over json"));

    checkRecords(
        "Did not find log message about another tool",
        executorLogSink,
        Level.SEVERE,
        logRecord -> {
          String message = logRecord.getMessage();
          if (!message.contains("Received invalid downward protocol message")) {
            return false;
          }
          return logRecord
              .getThrown()
              .getMessage()
              .equals(
                  "Reached max number(10) of allowed downward api nested tools without any events sent");
        });
  }

  @Test
  public void multipleJsonProtocolClientsAndThenMalformedData()
      throws IOException, InterruptedException {
    int fakeBytesToWrite = 100_000;

    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try (NamedPipeWriter writer =
                          NamedPipeFactory.getFactory()
                              .connectAsWriter(Paths.get(namedPipeReader.getName()));
                      OutputStream outputStream = writer.getOutputStream()) {
                    String protocolMessage = null;

                    ImmutableList<String> messages = getMessages();
                    for (int i = 0; i < messages.size(); i++) {
                      String message = messages.get(i);
                      if (i == 0) {
                        protocolMessage = message;
                      }
                      outputStream.write(message.getBytes(StandardCharsets.UTF_8));

                      if (i % 2 == 0) {
                        outputStream.write(protocolMessage.getBytes(StandardCharsets.UTF_8));
                      }

                      if (i == messages.size() - 1) {
                        // invalid symbol
                        outputStream.write(
                            String.format("_%s", System.lineSeparator())
                                .getBytes(StandardCharsets.UTF_8));
                        // fake bytes
                        for (int j = 0; j < fakeBytesToWrite; j++) {
                          outputStream.write(i % 42);
                        }
                      }
                    }

                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);
    DownwardApiExecutionResult executionResult = launchAndExecute(processExecutor);
    verifyExecutionResult(executionResult);

    checkRecords(
        "Did not find log message about read and drop " + fakeBytesToWrite + " fake bytes",
        executorLogSink,
        Level.INFO,
        logRecord -> {
          String message = logRecord.getMessage();
          return message.equals(
                  "Read and drop "
                      + fakeBytesToWrite
                      + " total bytes from named pipe: "
                      + namedPipeReader.getName())
              && message.endsWith("bytes from named pipe: " + namedPipeReader.getName());
        });

    checkRecords(
        "Did not find log message about another tool",
        jsonProtocolLogSing,
        Level.INFO,
        logRecord ->
            logRecord.getMessage().equals("Another tool wants to establish protocol over json"));
  }

  @Test
  public void multipleJsonProtocolClientsAndBinaryClient()
      throws IOException, InterruptedException {

    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try (NamedPipeWriter writer =
                          NamedPipeFactory.getFactory()
                              .connectAsWriter(Paths.get(namedPipeReader.getName()));
                      OutputStream outputStream = writer.getOutputStream()) {
                    String protocolMessage = null;

                    ImmutableList<String> messages = getMessages();
                    for (int i = 0; i < messages.size(); i++) {
                      String message = messages.get(i);
                      if (i == 0) {
                        protocolMessage = message;
                      }
                      if (i % 2 == 0) {
                        outputStream.write(protocolMessage.getBytes(StandardCharsets.UTF_8));
                      }
                      outputStream.write(message.getBytes(StandardCharsets.UTF_8));

                      if (i == messages.size() - 1) {
                        // switch to binary protocol
                        outputStream.write(
                            String.format(
                                    "%s%s",
                                    DownwardProtocolType.BINARY.getProtocolId(),
                                    System.lineSeparator())
                                .getBytes(StandardCharsets.UTF_8));
                      }
                    }

                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);
    DownwardApiExecutionResult executionResult = launchAndExecute(processExecutor);
    verifyExecutionResult(executionResult);

    checkRecords(
        "Did not find log message about change to binary protocol",
        executorLogSink,
        Level.SEVERE,
        logRecord -> {
          String message = logRecord.getMessage();
          if (!message.contains("Received invalid downward protocol message")) {
            return false;
          }
          return logRecord
              .getThrown()
              .getMessage()
              .equals(
                  "Invoked tool wants to change downward protocol from json to binary one. It is not supported!");
        });
  }

  @Test
  public void hugeMalformedDataInsteadOfProtocolType() throws IOException, InterruptedException {
    int maxCharsRead = 1 + System.lineSeparator().length();
    int fakeBytesToWrite = 100_000;
    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try (NamedPipeWriter writer =
                          NamedPipeFactory.getFactory()
                              .connectAsWriter(Paths.get(namedPipeReader.getName()));
                      OutputStream outputStream = writer.getOutputStream()) {
                    // fake bytes
                    for (int i = 0; i < fakeBytesToWrite + maxCharsRead; i++) {
                      outputStream.write(42);
                    }
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);
    verifyExecutionResult(result);

    checkRecords(
        "Did not find log message about no finding of expected EOL delimiter",
        executorLogSink,
        Level.SEVERE,
        logRecord -> {
          String message = logRecord.getMessage();
          if (message.startsWith("Received invalid downward protocol")) {

            String causeMessage = logRecord.getThrown().getMessage();
            return causeMessage.equals(
                "Cannot find an expected EOL delimiter. Read over than " + maxCharsRead + " chars");
          }
          return false;
        });

    checkRecords(
        "Did not find log message about read and drop " + fakeBytesToWrite + " fake bytes",
        executorLogSink,
        Level.INFO,
        logRecord -> {
          String message = logRecord.getMessage();
          return message.equals(
                  "Read and drop "
                      + fakeBytesToWrite
                      + " total bytes from named pipe: "
                      + namedPipeReader.getName())
              && message.endsWith("bytes from named pipe: " + namedPipeReader.getName());
        });
  }

  @Test
  public void hugeMalformedDataInsteadOfMessageLength() throws IOException, InterruptedException {
    int maxCharsRead = 5 + System.lineSeparator().length();
    int fakeBytesToWrite = 100_000;
    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try (NamedPipeWriter writer =
                          NamedPipeFactory.getFactory()
                              .connectAsWriter(Paths.get(namedPipeReader.getName()));
                      OutputStream outputStream = writer.getOutputStream()) {
                    // write a valid protocol type
                    for (int i = 0; i < 3; i++) {
                      outputStream.write(
                          String.format(
                                  "%s%s",
                                  DownwardProtocolType.JSON.getProtocolId(), System.lineSeparator())
                              .getBytes(StandardCharsets.UTF_8));
                    }

                    // fake bytes
                    for (int i = 0; i < fakeBytesToWrite + maxCharsRead; i++) {
                      outputStream.write(42);
                    }
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);
    verifyExecutionResult(result);

    checkRecords(
        "Did not find log message about no finding of expected EOL delimiter",
        executorLogSink,
        Level.SEVERE,
        logRecord -> {
          String message = logRecord.getMessage();
          if (message.equals("Received invalid downward protocol message")) {
            String errorMessage = logRecord.getThrown().getMessage();
            return errorMessage.equals(
                "Cannot find an expected EOL delimiter. Read over than " + maxCharsRead + " chars");
          }
          return false;
        });

    checkRecords(
        "Did not find log message about read and drop " + fakeBytesToWrite + " fake bytes",
        executorLogSink,
        Level.INFO,
        logRecord -> {
          String message = logRecord.getMessage();
          return message.equals(
                  "Read and drop "
                      + fakeBytesToWrite
                      + " total bytes from named pipe: "
                      + namedPipeReader.getName())
              && message.endsWith("bytes from named pipe: " + namedPipeReader.getName());
        });
  }

  @Test
  public void namedPipeClosedTooEarly() throws IOException, InterruptedException {
    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try {
                    namedPipeReader.close();
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipeReader, buckEventBus, clock, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);
    verifyExecutionResult(result);

    checkRecords(
        "Did not find log message about cannot connect to named pipe",
        executorLogSink,
        Level.INFO,
        logRecord ->
            logRecord
                .getMessage()
                .equals("Cannot connect to a named pipe: " + namedPipeReader.getName()));

    checkRecords(
        "Did not find log message about pipe is already closed",
        executorLogSink,
        Level.INFO,
        logRecord ->
            logRecord
                .getMessage()
                .equals("Named pipe " + namedPipeReader.getName() + " is already closed."));
  }

  @Test
  public void generalUnhandledException() throws IOException, InterruptedException {
    NamedPipeReader namedPipe = new TestNamedPipeWithException(namedPipeReader);
    String namedPipeName = namedPipe.getName();
    ProcessExecutorParams params = getProcessExecutorParams(namedPipeName, buckEventBus);

    FakeProcess fakeProcess = new FakeProcess(0);

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipe, buckEventBus, clock, params, fakeProcess);

    DownwardApiExecutionResult result;
    try {
      result = launchAndExecute(processExecutor);
    } finally {
      namedPipeReader.close();
    }
    verifyExecutionResult(result);

    checkRecords(
        "Did not find debug log message about unhandled exception",
        executorLogSink,
        Level.WARNING,
        logRecord -> {
          String message = logRecord.getMessage();
          return message.startsWith("Unhandled exception while reading from named pipe: ")
              && message.endsWith(namedPipeName);
        });
  }

  @Test
  public void downwardApiWithClientsEventHandlers() throws IOException, InterruptedException {
    ProcessExecutorParams params =
        getProcessExecutorParams(namedPipeReader.getName(), buckEventBus);

    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try {
                    writeIntoNamedPipeProcess(namedPipeReader.getName());
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    AtomicInteger eventsReceivedByClientsHandlers = new AtomicInteger();
    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(
            namedPipeReader,
            buckEventBus,
            clock,
            params,
            fakeProcess,
            (namedPipe, context) ->
                new CustomNamedEventPipeHandler(
                    namedPipe, context, eventsReceivedByClientsHandlers));

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);
    verifyExecutionResult(result);

    waitTillEventsProcessed();
    assertThat(eventsReceivedByClientsHandlers.get(), equalTo(1 + 2 + 10 * 2 + 100 * 3 + 500));
  }

  private void verifyLogEvent() {
    List<LogRecord> records = testToolLogSink.getRecords();
    LogRecord logRecord = Iterables.getOnlyElement(records);
    assertThat(logRecord.getLevel(), equalTo(Level.WARNING));
    assertThat(
        logRecord,
        TestLogSink.logRecordWithMessage(containsString("log message! show me to user!!!!")));
  }

  private void verifyConsoleEvent(BuckEvent consoleEvent) {
    assertTrue(consoleEvent instanceof com.facebook.buck.event.ConsoleEvent);
    com.facebook.buck.event.ConsoleEvent buckConsoleEvent =
        (com.facebook.buck.event.ConsoleEvent) consoleEvent;
    assertEquals("console message! show me to user!!!!", buckConsoleEvent.getMessage());
    assertEquals(Level.INFO, buckConsoleEvent.getLevel());
  }

  private void verifyStepEvent(
      long epochMilli,
      BuckEvent buckEvent,
      String eventName,
      String category,
      String description,
      int expectedRelativeDuration) {
    assertTrue(buckEvent instanceof com.facebook.buck.event.StepEvent);
    com.facebook.buck.event.StepEvent event = (com.facebook.buck.event.StepEvent) buckEvent;
    assertEquals(eventName, event.getEventName());
    assertEquals(category, event.getCategory());
    assertEquals(description, event.getDescription());
    verifyDuration(epochMilli, expectedRelativeDuration, event.getTimestampMillis());
  }

  private void verifyChromeTraceEvent(
      long epochMilli,
      BuckEvent chromeTraceEvent,
      String category,
      SimplePerfEvent.Type type,
      ImmutableMap<String, Object> attributes,
      int expectedRelativeTime) {

    assertTrue(chromeTraceEvent instanceof SimplePerfEvent);
    SimplePerfEvent simplePerfEvent = (SimplePerfEvent) chromeTraceEvent;
    assertEquals(category, simplePerfEvent.getCategory());
    assertEquals(type, simplePerfEvent.getEventType());
    assertEquals(attributes, simplePerfEvent.getEventInfo());
    verifyDuration(epochMilli, expectedRelativeTime, simplePerfEvent.getTimestampMillis());
  }

  private void verifyExternalEvent(BuckEvent buckEvent) {
    assertTrue(buckEvent instanceof com.facebook.buck.event.ExternalEvent);
    com.facebook.buck.event.ExternalEvent externalEvent =
        (com.facebook.buck.event.ExternalEvent) buckEvent;
    assertEquals(
        ImmutableMap.of(
            "errorMessageKey",
            "error message! show me to user!!!!",
            "buildTarget",
            "//test/foo:bar"),
        externalEvent.getData());
  }

  private void verifyDuration(long epochMilli, int expectedRelativeDuration, long eventMillis) {
    long relativeTimeInMillis = epochMilli - eventMillis;
    int diffInSeconds =
        (int) (TimeUnit.MILLISECONDS.toSeconds(relativeTimeInMillis) - expectedRelativeDuration);
    int diffThreshold = 1;
    assertTrue(
        "Diff in seconds: " + diffInSeconds + " should be less than threshold: " + diffThreshold,
        diffInSeconds <= diffThreshold);
  }

  private void writeIntoNamedPipeProcess(String namedPipeName) throws IOException {
    NamedPipeFactory namedPipeFactory = NamedPipeFactory.getFactory();
    try (NamedPipeWriter namedPipe = namedPipeFactory.connectAsWriter(Paths.get(namedPipeName));
        OutputStream outputStream = namedPipe.getOutputStream()) {
      for (String message : getMessages()) {
        LOG.info("Writing into named pipe: %s%s", System.lineSeparator(), message);
        outputStream.write(message.getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  private ImmutableList<String> getMessages() throws IOException {
    DownwardProtocolType protocolType = DownwardProtocolType.JSON;
    DownwardProtocol downwardProtocol = protocolType.getDownwardProtocol();

    ImmutableList.Builder<String> builder = ImmutableList.builder();

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    protocolType.writeDelimitedTo(outputStream);
    builder.add(outputStream.toString(StandardCharsets.UTF_8.name()));

    // finished without start event
    builder.add(
        TestUtils.stepEvent(downwardProtocol, FINISHED, "launched_process_orphan step started", 1));

    builder.add(
        TestUtils.stepEvent(downwardProtocol, STARTED, "launched_process step started", 50));
    builder.add(consoleEvent(downwardProtocol));
    builder.add(logEvent(downwardProtocol));
    builder.add(
        chromeTraceEvent(
            downwardProtocol,
            ChromeTraceEventStatus.BEGIN,
            "category_1",
            100,
            ImmutableMap.of("key1", "value1", "key2", "value2")));
    builder.add(
        TestUtils.stepEvent(downwardProtocol, FINISHED, "launched_process step finished", 55));
    builder.add(
        chromeTraceEvent(
            downwardProtocol,
            ChromeTraceEventStatus.END,
            "category_123",
            150,
            ImmutableMap.of("key3", "value3")));
    builder.add(externalEvent(downwardProtocol));

    return builder.build();
  }

  private void verifyExecutionResult(DownwardApiExecutionResult executionResult) {
    assertEquals("Process should exit with EXIT_SUCCESS", 0, executionResult.getExitCode());
    assertTrue("Reader thread is not terminated!", executionResult.isReaderThreadTerminated());
    assertFalse(
        "Named pipe file has to be deleted!", Files.exists(Paths.get(namedPipeReader.getName())));
    assertNotNull("Named pipe has not been created!", namedPipeReader);
    assertTrue("Named pipe has to be closed.", namedPipeReader.isClosed());
  }
}
