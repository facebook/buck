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

package com.facebook.buck.step.isolatedsteps;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.downward.model.ConsoleEvent;
import com.facebook.buck.downward.model.EventTypeMessage;
import com.facebook.buck.downward.model.StepEvent;
import com.facebook.buck.downwardapi.protocol.DownwardProtocol;
import com.facebook.buck.downwardapi.protocol.DownwardProtocolType;
import com.facebook.buck.downwardapi.testutil.StepEventMatcher;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.event.isolated.DefaultIsolatedEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.common.MkdirIsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.RmIsolatedStep;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Duration;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class IsolatedStepsRunnerTest {

  private static final Instant AT_TIME = Instant.parse("2020-12-15T12:13:14.123456789Z");
  private static final int CLOCK_SHIFT_IN_SECONDS = 123;

  private static final Verbosity VERBOSITY_FOR_TEST = Verbosity.STANDARD_INFORMATION;
  private static final Ansi ANSI_FOR_TEST = new Ansi(true);
  private static final BuildId BUILD_UUID_FOR_TEST = new BuildId("my_build");
  private static final String ACTION_ID = "my_action_id";

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  private ProjectFilesystem projectFilesystem;
  private File downwardApiFile;

  @Before
  public void setUp() throws Exception {
    projectFilesystem =
        new FakeProjectFilesystem(CanonicalCellName.rootCell(), temporaryFolder.getRoot());
    downwardApiFile = temporaryFolder.newFile("tmp").toFile();
  }

  @Test
  public void canExecuteSteps() throws Exception {
    AbsPath tempFile = temporaryFolder.newFile("temp_file");
    RelPath relativeTempFile =
        ProjectFilesystemUtils.relativize(projectFilesystem.getRootPath(), tempFile);
    assertTrue(tempFile.toFile().exists());

    RelPath relativeDirToCreate = RelPath.get("dir_to_create");
    Path dirToCreate =
        ProjectFilesystemUtils.getPathForRelativePath(
            projectFilesystem.getRootPath(), relativeDirToCreate);
    assertFalse(dirToCreate.toFile().exists());

    RmIsolatedStep rmIsolatedStep = RmIsolatedStep.of(relativeTempFile);
    MkdirIsolatedStep mkdirIsolatedStep = MkdirIsolatedStep.of(relativeDirToCreate);
    ImmutableList<IsolatedStep> steps = ImmutableList.of(rmIsolatedStep, mkdirIsolatedStep);

    IsolatedExecutionContext context = createContext(projectFilesystem.getRootPath());
    StepExecutionResult result = IsolatedStepsRunner.execute(steps, context);

    assertThat(result, equalTo(StepExecutionResults.SUCCESS));
    assertFalse(tempFile.toFile().exists());
    assertTrue(dirToCreate.toFile().exists());

    DownwardProtocol protocol = DownwardProtocolType.BINARY.getDownwardProtocol();
    InputStream inputStream = new FileInputStream(downwardApiFile);

    assertEventIdsAreEqual(
        getAndAssertStepEvent(
            rmIsolatedStep, StepEvent.StepStatus.STARTED, protocol, inputStream, context),
        getAndAssertStepEvent(
            rmIsolatedStep, StepEvent.StepStatus.FINISHED, protocol, inputStream, context));
    assertEventIdsAreEqual(
        getAndAssertStepEvent(
            mkdirIsolatedStep, StepEvent.StepStatus.STARTED, protocol, inputStream, context),
        getAndAssertStepEvent(
            mkdirIsolatedStep, StepEvent.StepStatus.FINISHED, protocol, inputStream, context));
  }

  @Test
  public void logsErrorIfStepExecutionFails() throws Exception {
    IsolatedStep isolatedStep =
        new IsolatedStep() {
          @Override
          public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context) {
            return StepExecutionResults.ERROR;
          }

          @Override
          public String getIsolatedStepDescription(IsolatedExecutionContext context) {
            return "test_description";
          }

          @Override
          public String getShortName() {
            return "test_short_name";
          }
        };
    ImmutableList<IsolatedStep> step = ImmutableList.of(isolatedStep);
    IsolatedExecutionContext context = createContext(projectFilesystem.getRootPath());

    StepExecutionResult result = IsolatedStepsRunner.execute(step, context);

    assertThat(result, equalTo(StepExecutionResults.ERROR));
    String expected =
        "Failed to execute steps"
            + System.lineSeparator()
            + "com.facebook.buck.step.StepFailedException: Command failed with exit code 1."
            + System.lineSeparator()
            + "  When running <test_description>.";

    DownwardProtocol protocol = DownwardProtocolType.BINARY.getDownwardProtocol();
    InputStream inputStream = new FileInputStream(downwardApiFile);

    assertEventIdsAreEqual(
        getAndAssertStepEvent(
            isolatedStep, StepEvent.StepStatus.STARTED, protocol, inputStream, context),
        getAndAssertStepEvent(
            isolatedStep, StepEvent.StepStatus.FINISHED, protocol, inputStream, context));

    EventTypeMessage.EventType actualEventType = protocol.readEventType(inputStream);
    ConsoleEvent actualConsoleEvent = protocol.readEvent(inputStream, actualEventType);
    assertThat(actualEventType, equalTo(EventTypeMessage.EventType.CONSOLE_EVENT));
    assertThat(actualConsoleEvent.getMessage(), Matchers.containsStringIgnoringCase(expected));
  }

  @Test
  public void logsErrorIfInterrupted() throws Exception {
    IsolatedStep isolatedStep =
        new IsolatedStep() {
          @Override
          public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context) {
            Thread.currentThread().interrupt();
            return StepExecutionResults.SUCCESS;
          }

          @Override
          public String getIsolatedStepDescription(IsolatedExecutionContext context) {
            return "test_description";
          }

          @Override
          public String getShortName() {
            return "test_short_name";
          }
        };
    ImmutableList<IsolatedStep> step = ImmutableList.of(isolatedStep);
    IsolatedExecutionContext context = createContext(projectFilesystem.getRootPath());

    StepExecutionResult result = IsolatedStepsRunner.execute(step, context);

    assertThat(result, equalTo(StepExecutionResults.ERROR));
    String expected =
        "Received interrupt"
            + System.lineSeparator()
            + "java.lang.InterruptedException: Thread was interrupted inside the executed step: test_short_name";

    DownwardProtocol protocol = DownwardProtocolType.BINARY.getDownwardProtocol();
    InputStream inputStream = new FileInputStream(downwardApiFile);

    assertEventIdsAreEqual(
        getAndAssertStepEvent(
            isolatedStep, StepEvent.StepStatus.STARTED, protocol, inputStream, context),
        getAndAssertStepEvent(
            isolatedStep, StepEvent.StepStatus.FINISHED, protocol, inputStream, context));

    EventTypeMessage.EventType actualEventType = protocol.readEventType(inputStream);
    ConsoleEvent actualConsoleEvent = protocol.readEvent(inputStream, actualEventType);
    assertThat(actualEventType, equalTo(EventTypeMessage.EventType.CONSOLE_EVENT));
    assertThat(actualConsoleEvent.getMessage(), Matchers.containsStringIgnoringCase(expected));
  }

  private IsolatedExecutionContext createContext(AbsPath root) throws Exception {
    long startExecutionMillis = AT_TIME.toEpochMilli();
    IsolatedEventBus buckEventBus =
        new DefaultIsolatedEventBus(
            BUILD_UUID_FOR_TEST,
            new FileOutputStream(downwardApiFile),
            FakeClock.of(
                startExecutionMillis + TimeUnit.SECONDS.toMillis(CLOCK_SHIFT_IN_SECONDS), 0),
            MoreExecutors.newDirectExecutorService(),
            DefaultIsolatedEventBus.DEFAULT_SHUTDOWN_TIMEOUT_MS,
            startExecutionMillis,
            DownwardProtocolType.BINARY.getDownwardProtocol());
    Console console = new Console(VERBOSITY_FOR_TEST, System.out, System.err, ANSI_FOR_TEST);
    ProcessExecutor defaultProcessExecutor = new DefaultProcessExecutor(console);

    return IsolatedExecutionContext.of(
        buckEventBus,
        console,
        Platform.detect(),
        defaultProcessExecutor,
        AbsPath.get(root.toString()),
        ACTION_ID,
        FakeClock.doNotCare());
  }

  private StepEvent getAndAssertStepEvent(
      IsolatedStep step,
      StepEvent.StepStatus stepStatus,
      DownwardProtocol protocol,
      InputStream inputStream,
      IsolatedExecutionContext context)
      throws Exception {
    StepEvent expectedStepStartEvent =
        StepEvent.newBuilder()
            .setDescription(step.getIsolatedStepDescription(context))
            .setStepType(step.getShortName())
            .setStepStatus(stepStatus)
            .setDuration(Duration.newBuilder().setSeconds(CLOCK_SHIFT_IN_SECONDS).build())
            .build();
    EventTypeMessage.EventType actualEventType = protocol.readEventType(inputStream);
    StepEvent actualEvent = protocol.readEvent(inputStream, actualEventType);
    assertThat(actualEventType, equalTo(EventTypeMessage.EventType.STEP_EVENT));
    assertThat(actualEvent, StepEventMatcher.equalsStepEvent(expectedStepStartEvent));
    return actualEvent;
  }

  private void assertEventIdsAreEqual(StepEvent event1, StepEvent event2) {
    assertThat(event1.getEventId(), equalTo(event2.getEventId()));
  }
}
