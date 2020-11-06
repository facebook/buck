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

import static com.facebook.buck.downward.model.EventTypeMessage.EventType.CHROME_TRACE_EVENT;
import static com.facebook.buck.downward.model.EventTypeMessage.EventType.CONSOLE_EVENT;
import static com.facebook.buck.downward.model.EventTypeMessage.EventType.EXTERNAL_EVENT;
import static com.facebook.buck.downward.model.EventTypeMessage.EventType.LOG_EVENT;
import static com.facebook.buck.downward.model.EventTypeMessage.EventType.STEP_EVENT;
import static com.facebook.buck.downward.model.StepEvent.StepStatus.FINISHED;
import static com.facebook.buck.downward.model.StepEvent.StepStatus.STARTED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.downward.model.ChromeTraceEvent;
import com.facebook.buck.downward.model.ChromeTraceEvent.ChromeTraceEventStatus;
import com.facebook.buck.downward.model.ConsoleEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.ExternalEvent;
import com.facebook.buck.downward.model.LogEvent;
import com.facebook.buck.downward.model.LogLevel;
import com.facebook.buck.downward.model.StepEvent;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.external.events.StepEventExternalInterface;
import com.facebook.buck.io.namedpipes.NamedPipe;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.testutil.TestLogSink;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.Subscribe;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Duration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DownwardApiProcessExecutorTest {

  private static final Logger LOG = Logger.get(DownwardApiProcessExecutorTest.class);

  private static final String TEST_LOGGER_NAME = "crazy.tool.name";
  private static final ConsoleParams CONSOLE_PARAMS =
      ConsoleParams.of(false, Verbosity.STANDARD_INFORMATION);
  private static final String TEST_COMMAND = "test_command";
  private static final String TEST_ACTION_ID = "test_action_id";

  @Rule public TestLogSink logSinkFromTest = new TestLogSink(TEST_LOGGER_NAME);

  @Rule
  public TestLogSink logSinkFromExecutor =
      new TestLogSink(DownwardApiProcessExecutor.class.getName());

  private NamedPipeReader namedPipe;
  private Instant instant;
  private BuckEventBus buckEventBus;
  private ProcessExecutorParams params;

  private static class TestListener {

    private final AtomicInteger counter = new AtomicInteger(-1);
    private final Map<Integer, BuckEvent> events = new HashMap<>();

    @Subscribe
    public void console(com.facebook.buck.event.ConsoleEvent event) {
      handleEvent(event);
    }

    @Subscribe
    public void chromeTrace(SimplePerfEvent event) {
      handleEvent(event);
    }

    @Subscribe
    public void step(com.facebook.buck.event.StepEvent event) {
      handleEvent(event);
    }

    @Subscribe
    public void external(com.facebook.buck.event.ExternalEvent event) {
      handleEvent(event);
    }

    private void handleEvent(BuckEvent event) {
      events.put(counter.incrementAndGet(), event);
    }
  }

  @Before
  public void setUp() throws Exception {
    namedPipe = NamedPipeFactory.getFactory().createAsReader();
    instant = Instant.now();
    buckEventBus =
        BuckEventBusForTests.newInstance(
            new SettableFakeClock(instant.toEpochMilli(), instant.getNano()));
    params = getProcessExecutorParams(namedPipe, buckEventBus);
  }

  @Test(timeout = 10_000)
  public void downwardApiWithNoWriters() throws IOException, InterruptedException {
    // do nothing
    FakeProcess fakeProcess = new FakeProcess(Optional.of(() -> Optional.empty()));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipe, buckEventBus, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);
    assertEquals(0, result.getExitCode());
    assertTrue("Reader thread is not terminated!", result.isReaderThreadTerminated());
  }

  @Test(timeout = 10_000)
  public void downwardApi() throws IOException, InterruptedException {
    TestListener listener = new TestListener();
    long epochSecond = instant.getEpochSecond();
    buckEventBus.register(listener);

    ProcessExecutorParams params = getProcessExecutorParams(namedPipe, buckEventBus);

    FakeProcess fakeProcess =
        new FakeProcess(
            Optional.of(
                () -> {
                  try {
                    writeIntoNamedPipeProcess(namedPipe.getName());
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipe, buckEventBus, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);
    assertEquals(0, result.getExitCode());
    assertTrue("Reader thread is not terminated!", result.isReaderThreadTerminated());

    Map<Integer, BuckEvent> events = listener.events;
    assertEquals(6, events.size());

    long currentThreadId = Thread.currentThread().getId();
    for (BuckEvent buckEvent : events.values()) {
      assertEquals(
          "Thread id for events has to be equals to thread id of the invoking thread. Failed event: "
              + buckEvent,
          currentThreadId,
          buckEvent.getThreadId());
    }

    // step start event
    verifyStepEvent(
        epochSecond,
        events.get(0),
        StepEventExternalInterface.STEP_STARTED,
        "crazy_stuff",
        "launched_process step started",
        50);

    // console event
    verifyConsoleEvent(events.get(1));

    // chrome trace start event
    verifyChromeTraceEvent(
        epochSecond,
        events.get(2),
        "category_1",
        SimplePerfEvent.Type.STARTED,
        ImmutableMap.of("key1", "value1", "key2", "value2"),
        100);

    // step finished event
    verifyStepEvent(
        epochSecond,
        events.get(3),
        StepEventExternalInterface.STEP_FINISHED,
        "crazy_stuff",
        // the same as in started event
        "launched_process step started",
        55);

    // chrome trace finished event
    verifyChromeTraceEvent(
        epochSecond,
        events.get(4),
        "category_1",
        SimplePerfEvent.Type.FINISHED,
        ImmutableMap.of("key3", "value3"),
        150);

    // log event
    verifyLogEvent();

    // verify error event
    verifyExternalEvent(events.get(5));

    assertFalse("Named pipe file has to be deleted!", Files.exists(Paths.get(namedPipe.getName())));
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

  @Test(timeout = 10_000)
  public void invalidProtocol() throws IOException, InterruptedException {
    FakeProcess fakeProcess =
        new FakeProcess(
            1,
            Optional.of(
                () -> {
                  try (NamedPipeWriter writer =
                          NamedPipeFactory.getFactory()
                              .connectAsWriter(Paths.get(namedPipe.getName()));
                      OutputStream outputStream = writer.getOutputStream()) {
                    // "u" is not a valid protocol
                    outputStream.write("u".getBytes(StandardCharsets.UTF_8));
                    outputStream.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipe, buckEventBus, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);

    boolean foundRecord = false;
    for (LogRecord logRecord : logSinkFromExecutor.getRecords()) {
      if (logRecord.getMessage().contains("Received invalid downward protocol")) {
        assertThat(logRecord.getThrown().getMessage(), containsString("Invalid protocol type: u"));
        foundRecord = true;
        break;
      }
    }
    if (!foundRecord) {
      fail("Did not find log message about unexpected protocol");
    }
    assertTrue("Reader thread is not terminated!", result.isReaderThreadTerminated());
    assertFalse("Named pipe file has to be deleted!", Files.exists(Paths.get(namedPipe.getName())));
  }

  @Test(timeout = 10_000)
  public void namedPipeClosedTooEarly() throws IOException, InterruptedException {
    FakeProcess fakeProcess =
        new FakeProcess(
            1,
            Optional.of(
                () -> {
                  try {
                    namedPipe.close();
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                  return Optional.empty();
                }));

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipe, buckEventBus, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);

    assertThat(
        getLogMessagesAsSingleString(logSinkFromExecutor).get(),
        stringContainsInOrder("Named pipe", namedPipe.getName(), "is closed"));
    assertTrue("Reader thread is not terminated!", result.isReaderThreadTerminated());
    assertFalse("Named pipe file has to be deleted!", Files.exists(Paths.get(namedPipe.getName())));
  }

  @Test(timeout = 10_000)
  public void otherIOException() throws IOException, InterruptedException {
    namedPipe =
        new NamedPipeReader() {
          @Override
          public InputStream getInputStream() throws IOException {
            throw new IOException("hello");
          }

          @Override
          public String getName() {
            return "unused";
          }

          @Override
          public void close() {}
        };
    params = getProcessExecutorParams(namedPipe, buckEventBus);

    FakeProcess fakeProcess = new FakeProcess(0);

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipe, buckEventBus, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);

    assertThat(
        getLogMessagesAsSingleString(logSinkFromExecutor).get(),
        stringContainsInOrder("Cannot read from named pipe: ", namedPipe.getName()));
    assertTrue("Reader thread is not terminated!", result.isReaderThreadTerminated());
  }

  @Test(timeout = 10_000)
  public void generalUnhandledException() throws IOException, InterruptedException {
    namedPipe =
        new NamedPipeReader() {
          @Override
          public InputStream getInputStream() {
            throw new RuntimeException("hello");
          }

          @Override
          public String getName() {
            return "unused";
          }

          @Override
          public void close() {}
        };
    params = getProcessExecutorParams(namedPipe, buckEventBus);

    FakeProcess fakeProcess = new FakeProcess(0);

    DownwardApiProcessExecutor processExecutor =
        getDownwardApiProcessExecutor(namedPipe, buckEventBus, params, fakeProcess);

    DownwardApiExecutionResult result = launchAndExecute(processExecutor);

    assertThat(
        getLogMessagesAsSingleString(logSinkFromExecutor).get(),
        stringContainsInOrder(
            "Unhandled exception while reading from named pipe: ", namedPipe.getName()));
    assertTrue("Reader thread is not terminated!", result.isReaderThreadTerminated());
  }

  private DownwardApiExecutionResult launchAndExecute(DownwardApiProcessExecutor processExecutor)
      throws InterruptedException, IOException {
    ProcessExecutor.Result result = processExecutor.launchAndExecute(getProcessExecutorParams());
    return (DownwardApiExecutionResult) result;
  }

  private DownwardApiProcessExecutor getDownwardApiProcessExecutor(
      NamedPipeReader namedPipe,
      BuckEventBus buckEventBus,
      ProcessExecutorParams params,
      FakeProcess fakeProcess) {
    ImmutableMap.Builder<ProcessExecutorParams, FakeProcess> fakeProcessesBuilder =
        ImmutableMap.<ProcessExecutorParams, FakeProcess>builder().put(params, fakeProcess);

    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(fakeProcessesBuilder.build());

    return new DownwardApiProcessExecutor(
        fakeProcessExecutor,
        CONSOLE_PARAMS,
        buckEventBus.isolated(),
        TEST_ACTION_ID,
        new NamedPipeFactory() {
          @Override
          public NamedPipeWriter createAsWriter() {
            throw new UnsupportedOperationException();
          }

          @Override
          public NamedPipeReader createAsReader() {
            return namedPipe;
          }

          @Override
          public NamedPipeWriter connectAsWriter(Path namedPipePath) {
            throw new UnsupportedOperationException();
          }

          @Override
          public NamedPipeReader connectAsReader(Path namedPipePath) {
            throw new UnsupportedOperationException();
          }
        });
  }

  private ProcessExecutorParams getProcessExecutorParams() {
    return ProcessExecutorParams.ofCommand(TEST_COMMAND)
        .withEnvironment(
            ImmutableMap.of(
                "SOME_ENV1",
                "VALUE1",
                "BUCK_BUILD_UUID",
                "TO_BE_REPLACED",
                "BUCK_ACTION_ID",
                "TO_BE_REPLACED",
                "SOME_ENV2",
                "VALUE2"));
  }

  private ProcessExecutorParams getProcessExecutorParams(
      NamedPipe namedPipe, BuckEventBus buckEventBus) {
    ImmutableMap.Builder<String, String> envsBuilder = ImmutableMap.builder();
    envsBuilder.put("SOME_ENV1", "VALUE1");
    envsBuilder.put("SOME_ENV2", "VALUE2");
    envsBuilder.put("BUCK_VERBOSITY", CONSOLE_PARAMS.getVerbosity());
    envsBuilder.put("BUCK_ANSI_ENABLED", CONSOLE_PARAMS.isAnsiEscapeSequencesEnabled());
    envsBuilder.put("BUCK_BUILD_UUID", buckEventBus.getBuildId().toString());
    envsBuilder.put("BUCK_ACTION_ID", TEST_ACTION_ID);
    envsBuilder.put("BUCK_EVENT_PIPE", namedPipe.getName());
    ImmutableMap<String, String> envs = envsBuilder.build();

    return ProcessExecutorParams.builder()
        .setCommand(ImmutableList.of(TEST_COMMAND))
        .setEnvironment(envs)
        .build();
  }

  private void verifyLogEvent() {
    List<LogRecord> records = logSinkFromTest.getRecords();
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
      long epochSecond,
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
    long nanoTime = event.getNanoTime();
    verifyDuration(epochSecond, expectedRelativeDuration, nanoTime);
  }

  private void verifyChromeTraceEvent(
      long epochSecond,
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
    verifyDuration(epochSecond, expectedRelativeTime, simplePerfEvent.getNanoTime());
  }

  private void verifyDuration(long epochSecond, int expectedRelativeDuration, long nanoTime) {
    long eventTimeInSeconds = TimeUnit.NANOSECONDS.toSeconds(nanoTime);
    long relativeTimeInSeconds = eventTimeInSeconds - epochSecond;
    int diffInSeconds = (int) (relativeTimeInSeconds - expectedRelativeDuration);
    int diffThreshold = 2;
    assertTrue(
        "Diff in seconds: " + diffInSeconds + " should be less than threshold: " + diffThreshold,
        diffInSeconds <= diffThreshold);
  }

  private void writeIntoNamedPipeProcess(String namedPipeName)
      throws IOException, InterruptedException {

    try (NamedPipeWriter namedPipe =
        NamedPipeFactory.getFactory().connectAsWriter(Paths.get(namedPipeName))) {
      try (OutputStream outputStream = namedPipe.getOutputStream()) {
        for (String message : getMessages()) {
          LOG.info("Writing into named pipe: %s%s", System.lineSeparator(), message);
          outputStream.write(message.getBytes(StandardCharsets.UTF_8));
          TimeUnit.MILLISECONDS.sleep(100);
        }
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
    builder.add(stepEvent(downwardProtocol, FINISHED, "launched_process_orphan step started", 1));

    builder.add(stepEvent(downwardProtocol, STARTED, "launched_process step started", 50));
    builder.add(consoleEvent(downwardProtocol));
    builder.add(logEvent(downwardProtocol));
    builder.add(
        chromeTraceEvent(
            downwardProtocol,
            ChromeTraceEventStatus.BEGIN,
            "category_1",
            100,
            ImmutableMap.of("key1", "value1", "key2", "value2")));
    builder.add(stepEvent(downwardProtocol, FINISHED, "launched_process step finished", 55));
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

  private String stepEvent(
      DownwardProtocol downwardProtocol,
      StepEvent.StepStatus started,
      String description,
      long durationSeconds)
      throws IOException {
    StepEvent stepEvent =
        StepEvent.newBuilder()
            .setEventId(123)
            .setStepStatus(started)
            .setStepType("crazy_stuff")
            .setDescription(description)
            .setDuration(Duration.newBuilder().setSeconds(durationSeconds).setNanos(10).build())
            .build();

    return write(downwardProtocol, STEP_EVENT, stepEvent);
  }

  private String externalEvent(DownwardProtocol downwardProtocol) throws IOException {
    ExternalEvent externalEvent =
        ExternalEvent.newBuilder()
            .putAllData(
                ImmutableMap.of(
                    "errorMessageKey",
                    "error message! show me to user!!!!",
                    "buildTarget",
                    "//test/foo:bar"))
            .build();

    return write(downwardProtocol, EXTERNAL_EVENT, externalEvent);
  }

  private String consoleEvent(DownwardProtocol downwardProtocol) throws IOException {
    ConsoleEvent consoleEvent =
        ConsoleEvent.newBuilder()
            .setLogLevel(LogLevel.INFO)
            .setMessage("console message! show me to user!!!!")
            .build();

    return write(downwardProtocol, CONSOLE_EVENT, consoleEvent);
  }

  private String logEvent(DownwardProtocol downwardProtocol) throws IOException {
    LogEvent logEvent =
        LogEvent.newBuilder()
            .setLogLevel(LogLevel.WARN)
            .setLoggerName("crazy.tool.name")
            .setMessage("log message! show me to user!!!!")
            .build();

    return write(downwardProtocol, LOG_EVENT, logEvent);
  }

  private String chromeTraceEvent(
      DownwardProtocol downwardProtocol,
      ChromeTraceEventStatus status,
      String category,
      int relativeSeconds,
      ImmutableMap<String, String> attributes)
      throws IOException {
    ChromeTraceEvent chromeTraceEvent =
        ChromeTraceEvent.newBuilder()
            .setEventId(789)
            .setTitle("my_trace_event")
            .setCategory(category)
            .setStatus(status)
            .setDuration(Duration.newBuilder().setSeconds(relativeSeconds).setNanos(10).build())
            .putAllData(attributes)
            .build();

    return write(downwardProtocol, CHROME_TRACE_EVENT, chromeTraceEvent);
  }

  private String write(
      DownwardProtocol downwardProtocol,
      EventTypeMessage.EventType eventType,
      AbstractMessage message)
      throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    downwardProtocol.write(
        EventTypeMessage.newBuilder().setEventType(eventType).build(), message, outputStream);
    return outputStream.toString(StandardCharsets.UTF_8.name());
  }

  private Optional<String> getLogMessagesAsSingleString(TestLogSink logSink) {
    return logSink.getRecords().stream()
        .map(LogRecord::getMessage)
        .reduce((record, acc) -> String.join(System.lineSeparator(), record, acc));
  }
}
