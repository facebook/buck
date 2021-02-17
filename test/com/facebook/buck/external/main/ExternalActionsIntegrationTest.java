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

package com.facebook.buck.external.main;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.downwardapi.processexecutor.DefaultNamedPipeEventHandler;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.downwardapi.testutil.TestWindowsHandleFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StepEvent;
import com.facebook.buck.external.constants.ExternalBinaryBuckConstants;
import com.facebook.buck.external.parser.ExternalArgsParser;
import com.facebook.buck.io.namedpipes.windows.WindowsNamedPipeFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.testutil.ExecutorServiceUtils;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.TestLogSink;
import com.facebook.buck.testutil.integration.EnvironmentSanitizer;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Resources;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class ExternalActionsIntegrationTest {

  private static final String TEST_ACTION_ID = "test_action_id";
  private static final ConsoleParams CONSOLE_PARAMS =
      ConsoleParams.of(false, Verbosity.STANDARD_INFORMATION);

  private File buildableCommandFile;
  private BuckEventBus eventBusForTests;
  private BuckEventBusForTests.CapturingEventListener eventBusListener;
  private ProcessExecutor downwardApiProcessExecutor;
  private Path testBinary;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule
  public TestLogSink consoleEventLogSink =
      new TestLogSink(FakeBuckEventWritingAction.ConsoleEventStep.class.getName());

  @Rule
  public TestLogSink logEventLogSink =
      new TestLogSink(FakeBuckEventWritingAction.LogEventStep.class.getName());

  private static final TestWindowsHandleFactory TEST_WINDOWS_HANDLE_FACTORY =
      new TestWindowsHandleFactory();

  @BeforeClass
  public static void beforeClass() throws Exception {
    // override WindowsHandleFactory with a test one
    WindowsNamedPipeFactory.windowsHandleFactory = TEST_WINDOWS_HANDLE_FACTORY;
  }

  @Before
  public void setUp() throws Exception {
    buildableCommandFile = temporaryFolder.newFile("buildable_command").toFile();
    ProcessExecutor defaultExecutor = new DefaultProcessExecutor(new TestConsole());
    eventBusForTests = BuckEventBusForTests.newInstance();
    eventBusListener = new BuckEventBusForTests.CapturingEventListener();
    eventBusForTests.register(eventBusListener);
    downwardApiProcessExecutor =
        DownwardApiProcessExecutor.FACTORY.create(
            defaultExecutor,
            DefaultNamedPipeEventHandler.FACTORY,
            CONSOLE_PARAMS,
            eventBusForTests.isolated(),
            TEST_ACTION_ID,
            FakeClock.doNotCare());

    String packageName = getClass().getPackage().getName().replace('.', '/');
    URL binary = Resources.getResource(packageName + "/external_actions_bin_for_tests.jar");
    testBinary = temporaryFolder.getRoot().getPath().resolve("external_action.jar");
    try (FileOutputStream stream = new FileOutputStream(testBinary.toFile())) {
      stream.write(Resources.toByteArray(binary));
    }
  }

  @After
  public void tearDown() throws Exception {
    eventBusForTests.unregister(eventBusListener);
    eventBusForTests.close();
  }

  @Test
  public void executingBinaryExecutesExternalActions() throws Exception {
    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .addAllArgs(ImmutableList.of("test_path"))
            .putAllEnv(ImmutableMap.of())
            .setExternalActionClass(FakeMkdirExternalAction.class.getName())
            .build();
    writeBuildableCommand(buildableCommand);
    ProcessExecutorParams params = createProcessExecutorParams(createCmd());

    ProcessExecutor.Result result = launchAndExecute(downwardApiProcessExecutor, params);

    assertThat(result.getExitCode(), equalTo(0));
    AbsPath actualOutput = temporaryFolder.getRoot().resolve("test_path");
    assertTrue(Files.isDirectory(actualOutput.getPath()));

    StepEvent.Started expectedStartEvent =
        StepEvent.started("mkdir", String.format("mkdir -p %s", actualOutput.getPath()));
    StepEvent.Finished expectedFinishEvent = StepEvent.finished(expectedStartEvent, 0);
    List<String> actualStepEvents = eventBusListener.getStepEventLogMessages();
    assertThat(actualStepEvents, hasSize(2));
    assertThat(actualStepEvents.get(0), equalTo(expectedStartEvent.toLogMessage()));
    assertThat(actualStepEvents.get(1), equalTo(expectedFinishEvent.toLogMessage()));
  }

  @Test
  public void eventsAreSentBackToBuck() throws Exception {
    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .addAllArgs(ImmutableList.of("sneaky", "beaky"))
            .setExternalActionClass(FakeBuckEventWritingAction.class.getName())
            .putAllEnv(ImmutableMap.of())
            .build();
    writeBuildableCommand(buildableCommand);

    ProcessExecutorParams params = createProcessExecutorParams(createCmd());
    ProcessExecutor.Result result = launchAndExecute(downwardApiProcessExecutor, params);

    assertThat(result.getExitCode(), equalTo(0));
    verifyAllWindowsHandlesAreClosed();

    StepEvent.Started expectedStepStartEvent =
        StepEvent.started("console_event_step", "console event: sneaky");
    StepEvent.Finished expectedStepFinishEvent = StepEvent.finished(expectedStepStartEvent, 0);

    waitTillEventsProcessed();

    List<String> actualStepEventLogs = eventBusListener.getStepEventLogMessages();
    assertThat(actualStepEventLogs, hasSize(4));
    assertThat(actualStepEventLogs.get(0), equalTo(expectedStepStartEvent.toLogMessage()));
    assertThat(actualStepEventLogs.get(1), equalTo(expectedStepFinishEvent.toLogMessage()));

    expectedStepStartEvent = StepEvent.started("log_event_step", "log: beaky");
    expectedStepFinishEvent = StepEvent.finished(expectedStepStartEvent, 0);
    assertThat(actualStepEventLogs.get(2), equalTo(expectedStepStartEvent.toLogMessage()));
    assertThat(actualStepEventLogs.get(3), equalTo(expectedStepFinishEvent.toLogMessage()));

    ConsoleEvent expectedConsoleEvent = ConsoleEvent.info("sneaky");
    List<String> actualConsoleEventLogs = eventBusListener.getConsoleEventLogMessages();
    assertThat(
        Iterables.getOnlyElement(actualConsoleEventLogs),
        equalTo(expectedConsoleEvent.toLogMessage()));

    SimplePerfEvent.Started expectedPerfStartEvent =
        SimplePerfEvent.started(SimplePerfEvent.PerfEventTitle.of("test_perf_event_title"));
    List<String> actualPerfEvents = eventBusListener.getSimplePerfEvents();
    assertThat(actualPerfEvents, hasSize(2));
    assertThat(actualPerfEvents.get(0), equalTo(expectedPerfStartEvent.toLogMessage()));

    // SimplePerfEvent.Finished is not exposed. Grab its #toLogMessage implementation directly from
    // AbstractBuckEvent
    assertThat(actualPerfEvents.get(1), equalTo("PerfEvent.test_perf_event_title.Finished()"));

    String logMessagesFromConsoleEvent = getLogMessagesAsSingleString(consoleEventLogSink);
    assertThat(
        logMessagesFromConsoleEvent,
        containsString("Starting ConsoleEventStep execution for message sneaky!"));
    assertThat(
        logMessagesFromConsoleEvent,
        containsString("Finished ConsoleEventStep execution for message sneaky!"));

    String logMessagesFromLogEvent = getLogMessagesAsSingleString(logEventLogSink);
    assertThat(logMessagesFromLogEvent, containsString("beaky"));
  }

  private void waitTillEventsProcessed() throws InterruptedException {
    ExecutorServiceUtils.waitTillAllTasksCompleted(
        (ThreadPoolExecutor) DownwardApiProcessExecutor.HANDLER_THREAD_POOL);
  }

  private void verifyAllWindowsHandlesAreClosed() {
    if (Platform.detect() == Platform.WINDOWS) {
      TEST_WINDOWS_HANDLE_FACTORY.verifyAllCreatedHandlesClosed();
    }
  }

  @Test
  public void failsIfExpectedEnvVarsNotPresent() throws Exception {
    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .addAllArgs(ImmutableList.of("hello"))
            .setExternalActionClass(FakeBuckEventWritingAction.class.getName())
            .putAllEnv(ImmutableMap.of())
            .build();
    writeBuildableCommand(buildableCommand);
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .setCommand(createCmd())
            // Missing ExternalBinaryBuckConstants.ENV_RULE_CELL_ROOT environment variable
            .setEnvironment(EnvironmentSanitizer.getSanitizedEnvForTests(ImmutableMap.of()))
            .build();

    ProcessExecutor.Result result = launchAndExecute(downwardApiProcessExecutor, params);

    assertThat(result.getExitCode(), equalTo(1));
    assertThat(result.getStderr().get(), containsString("Missing env var: BUCK_RULE_CELL_ROOT"));
  }

  @Test
  public void failsIfExternalActionClassIsNotExternalAction() throws Exception {
    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .addAllArgs(ImmutableList.of("test_path"))
            .putAllEnv(ImmutableMap.of())
            .setExternalActionClass(ExternalArgsParser.class.getName())
            .build();
    writeBuildableCommand(buildableCommand);
    ProcessExecutorParams params = createProcessExecutorParams(createCmd());

    ProcessExecutor.Result result = launchAndExecute(downwardApiProcessExecutor, params);

    assertThat(result.getExitCode(), equalTo(1));
    assertThat(
        result.getStderr().get(),
        containsString(
            "com.facebook.buck.external.parser.ExternalArgsParser does not implement ExternalAction"));
  }

  @Test
  public void failsIfExpectedArgInBuildableCommandNotPresent() throws Exception {
    BuildableCommand buildableCommand =
        BuildableCommand.newBuilder()
            .addAllArgs(ImmutableList.of())
            .putAllEnv(ImmutableMap.of())
            .setExternalActionClass(FakeMkdirExternalAction.class.getName())
            .build();
    writeBuildableCommand(buildableCommand);
    ProcessExecutorParams params = createProcessExecutorParams(createCmd());

    ProcessExecutor.Result result = launchAndExecute(downwardApiProcessExecutor, params);

    assertThat(result.getExitCode(), equalTo(1));
    assertThat(
        result.getStderr().get(),
        containsString(
            String.format(
                "Failed to get steps from external action %s",
                FakeMkdirExternalAction.class.getName())));

    List<String> actualStepEvents = eventBusListener.getStepEventLogMessages();
    assertThat(actualStepEvents, is(empty()));
  }

  private void writeBuildableCommand(BuildableCommand buildableCommand) throws Exception {
    try (OutputStream outputStream = new FileOutputStream(buildableCommandFile)) {
      buildableCommand.writeTo(outputStream);
    }
  }

  private ImmutableList<String> createCmd() {
    return ImmutableList.of(
        JavaBuckConfig.getJavaBinCommand(),
        "-jar",
        testBinary.toString(),
        buildableCommandFile.getAbsolutePath());
  }

  private ProcessExecutorParams createProcessExecutorParams(ImmutableList<String> command) {
    return ProcessExecutorParams.builder()
        .setCommand(command)
        .setEnvironment(
            EnvironmentSanitizer.getSanitizedEnvForTests(
                ImmutableMap.of(
                    ExternalBinaryBuckConstants.ENV_RULE_CELL_ROOT,
                    temporaryFolder.getRoot().toString())))
        .build();
  }

  private ProcessExecutor.Result launchAndExecute(
      ProcessExecutor processExecutor, ProcessExecutorParams params) throws Exception {
    return processExecutor.launchAndExecute(
        params,
        ImmutableMap.of(),
        ImmutableSet.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private String getLogMessagesAsSingleString(TestLogSink logSink) {
    return logSink.getRecords().stream()
        .map(LogRecord::getMessage)
        .collect(Collectors.joining(System.lineSeparator()));
  }
}
