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

import com.facebook.buck.core.build.execution.context.actionid.ActionId;
import com.facebook.buck.downward.model.ChromeTraceEvent;
import com.facebook.buck.downward.model.ConsoleEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.ExternalEvent;
import com.facebook.buck.downward.model.LogEvent;
import com.facebook.buck.downward.model.LogLevel;
import com.facebook.buck.downward.model.StepEvent;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeReader;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.NamedPipeEventHandlerFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.Duration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

class TestUtils {

  static final String TEST_LOGGER_NAME = "crazy.tool.name";
  static final ActionId TEST_ACTION_ID = ActionId.of("test_action_id");
  private static final ConsoleParams CONSOLE_PARAMS =
      ConsoleParams.of(false, Verbosity.STANDARD_INFORMATION);
  private static final String TEST_COMMAND = "test_command";

  static String stepEvent(
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
            .setDuration(Duration.newBuilder().setSeconds(-durationSeconds).setNanos(-10).build())
            .setActionId(TEST_ACTION_ID.getValue())
            .build();

    return write(downwardProtocol, STEP_EVENT, stepEvent);
  }

  static String externalEvent(DownwardProtocol downwardProtocol) throws IOException {
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

  static String consoleEvent(DownwardProtocol downwardProtocol) throws IOException {
    ConsoleEvent consoleEvent =
        ConsoleEvent.newBuilder()
            .setLogLevel(LogLevel.INFO)
            .setMessage("console message! show me to user!!!!")
            .build();

    return write(downwardProtocol, CONSOLE_EVENT, consoleEvent);
  }

  static String logEvent(DownwardProtocol downwardProtocol) throws IOException {
    LogEvent logEvent =
        LogEvent.newBuilder()
            .setLogLevel(LogLevel.WARN)
            .setLoggerName(TEST_LOGGER_NAME)
            .setMessage("log message! show me to user!!!!")
            .build();

    return write(downwardProtocol, LOG_EVENT, logEvent);
  }

  static String chromeTraceEvent(
      DownwardProtocol downwardProtocol,
      ChromeTraceEvent.ChromeTraceEventStatus status,
      String category,
      int durationInSeconds,
      ImmutableMap<String, String> attributes)
      throws IOException {
    ChromeTraceEvent chromeTraceEvent =
        ChromeTraceEvent.newBuilder()
            .setEventId(789)
            .setTitle("my_trace_event")
            .setCategory(category)
            .setStatus(status)
            .setDuration(Duration.newBuilder().setSeconds(-durationInSeconds).setNanos(-10).build())
            .putAllData(attributes)
            .setActionId(TEST_ACTION_ID.getValue())
            .build();

    return write(downwardProtocol, CHROME_TRACE_EVENT, chromeTraceEvent);
  }

  static String write(
      DownwardProtocol downwardProtocol,
      EventTypeMessage.EventType eventType,
      AbstractMessage message)
      throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    downwardProtocol.write(
        EventTypeMessage.newBuilder().setEventType(eventType).build(), message, outputStream);
    return outputStream.toString(StandardCharsets.UTF_8.name());
  }

  static DownwardApiProcessExecutor getDownwardApiProcessExecutor(
      NamedPipeReader namedPipe,
      BuckEventBus buckEventBus,
      Clock clock,
      ProcessExecutorParams params,
      FakeProcess fakeProcess) {
    return getDownwardApiProcessExecutor(
        namedPipe, buckEventBus, clock, params, fakeProcess, DefaultNamedPipeEventHandler.FACTORY);
  }

  static DownwardApiProcessExecutor getDownwardApiProcessExecutor(
      NamedPipeReader namedPipe,
      BuckEventBus buckEventBus,
      Clock clock,
      ProcessExecutorParams params,
      FakeProcess fakeProcess,
      NamedPipeEventHandlerFactory namedPipeEventHandlerFactory) {
    ImmutableMap.Builder<ProcessExecutorParams, FakeProcess> fakeProcessesBuilder =
        ImmutableMap.<ProcessExecutorParams, FakeProcess>builder().put(params, fakeProcess);

    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(fakeProcessesBuilder.build());

    return new DownwardApiProcessExecutor(
        fakeProcessExecutor,
        CONSOLE_PARAMS,
        buckEventBus.isolated(),
        TestUtils.TEST_ACTION_ID,
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
        },
        clock,
        namedPipeEventHandlerFactory);
  }

  static ProcessExecutorParams getProcessExecutorParams() {
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

  static ProcessExecutorParams getProcessExecutorParams(
      String namedPipeName, BuckEventBus buckEventBus) {
    ImmutableMap.Builder<String, String> envsBuilder = ImmutableMap.builder();
    envsBuilder.put("SOME_ENV1", "VALUE1");
    envsBuilder.put("SOME_ENV2", "VALUE2");
    envsBuilder.put("BUCK_VERBOSITY", CONSOLE_PARAMS.getVerbosity());
    envsBuilder.put("BUCK_ANSI_ENABLED", CONSOLE_PARAMS.isAnsiEscapeSequencesEnabled());
    envsBuilder.put("BUCK_BUILD_UUID", buckEventBus.getBuildId().toString());
    envsBuilder.put("BUCK_ACTION_ID", TestUtils.TEST_ACTION_ID.getValue());
    envsBuilder.put("BUCK_EVENT_PIPE", namedPipeName);
    ImmutableMap<String, String> envs = envsBuilder.build();

    return ProcessExecutorParams.builder()
        .setCommand(ImmutableList.of(TEST_COMMAND))
        .setEnvironment(envs)
        .build();
  }

  static DownwardApiExecutionResult launchAndExecute(DownwardApiProcessExecutor processExecutor)
      throws InterruptedException, IOException {
    ProcessExecutor.Result result = processExecutor.launchAndExecute(getProcessExecutorParams());
    return (DownwardApiExecutionResult) result;
  }
}
