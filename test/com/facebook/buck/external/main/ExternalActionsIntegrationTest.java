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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.downwardapi.processexecutor.DownwardApiProcessExecutor;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StepEvent;
import com.facebook.buck.external.constants.ExternalBinaryBuckConstants;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.EnvironmentSanitizer;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
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
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ExternalActionsIntegrationTest {

  private static final String TEST_ACTION_ID = "test_action_id";
  private static final ConsoleParams CONSOLE_PARAMS =
      ConsoleParams.of(false, Verbosity.STANDARD_INFORMATION);

  private File buildableCommandFile;
  private ProcessExecutor defaultExecutor;
  private BuckEventBus eventBusForTests;
  private BuckEventBusForTests.CapturingEventListener eventBusListener;
  private ProcessExecutor downwardApiProcessExecutor;
  private Path testBinary;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {
    buildableCommandFile = temporaryFolder.newFile("buildable_command").toFile();
    defaultExecutor = new DefaultProcessExecutor(new TestConsole());
    eventBusForTests = BuckEventBusForTests.newInstance();
    eventBusListener = new BuckEventBusForTests.CapturingEventListener();
    eventBusForTests.register(eventBusListener);
    downwardApiProcessExecutor =
        DownwardApiProcessExecutor.FACTORY.create(
            defaultExecutor, CONSOLE_PARAMS, eventBusForTests.isolated(), TEST_ACTION_ID);

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
        StepEvent.started(
            "mkdir", String.format("mkdir -p %s", actualOutput.getPath()), UUID.randomUUID());
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
            .addAllArgs(ImmutableList.of("hello"))
            .setExternalActionClass(FakeBuckEventWritingAction.class.getName())
            .putAllEnv(ImmutableMap.of())
            .build();
    writeBuildableCommand(buildableCommand);

    ProcessExecutorParams params = createProcessExecutorParams(createCmd());
    ProcessExecutor.Result result = launchAndExecute(downwardApiProcessExecutor, params);

    assertThat(result.getExitCode(), equalTo(0));

    StepEvent.Started expectedStepStartEvent =
        StepEvent.started("console_event_step", "console event: hello", UUID.randomUUID());
    StepEvent.Finished expectedStepFinishEvent = StepEvent.finished(expectedStepStartEvent, 0);
    List<String> actualStepEventLogs = eventBusListener.getStepEventLogMessages();
    assertThat(actualStepEventLogs, hasSize(2));
    assertThat(actualStepEventLogs.get(0), equalTo(expectedStepStartEvent.toLogMessage()));
    assertThat(actualStepEventLogs.get(1), equalTo(expectedStepFinishEvent.toLogMessage()));

    ConsoleEvent expectedConsoleEvent = ConsoleEvent.info("hello");
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
  }

  private void writeBuildableCommand(BuildableCommand buildableCommand) throws Exception {
    try (OutputStream outputStream = new FileOutputStream(buildableCommandFile)) {
      buildableCommand.writeTo(outputStream);
    }
  }

  private ImmutableList<String> createCmd() {
    return ImmutableList.of(
        "java", "-jar", testBinary.toString(), buildableCommandFile.getAbsolutePath());
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
}
