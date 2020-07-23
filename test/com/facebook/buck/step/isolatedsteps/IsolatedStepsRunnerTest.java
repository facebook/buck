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
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.DefaultBuckEventBus;
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
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class IsolatedStepsRunnerTest {

  private static final Verbosity VERBOSITY_FOR_TEST = Verbosity.STANDARD_INFORMATION;
  private static final Ansi ANSI_FOR_TEST = new Ansi(true);
  private static final BuildId BUILD_UUID_FOR_TEST = new BuildId("my_build");

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() {
    projectFilesystem =
        new FakeProjectFilesystem(CanonicalCellName.rootCell(), temporaryFolder.getRoot());
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

    ImmutableList<IsolatedStep> steps =
        ImmutableList.of(
            RmIsolatedStep.of(relativeTempFile), MkdirIsolatedStep.of(relativeDirToCreate));

    StepExecutionResult result =
        IsolatedStepsRunner.execute(steps, createContext(projectFilesystem.getRootPath()));

    assertThat(result, equalTo(StepExecutionResults.SUCCESS));
    assertFalse(tempFile.toFile().exists());
    assertTrue(dirToCreate.toFile().exists());
  }

  @Test
  public void logsErrorIfStepExecutionFails() {
    ImmutableList<IsolatedStep> step =
        ImmutableList.of(
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
            });
    IsolatedExecutionContext context = createContext(projectFilesystem.getRootPath());
    BuckEventBusForTests.CapturingConsoleEventListener listener =
        new BuckEventBusForTests.CapturingConsoleEventListener();
    context.getBuckEventBus().register(listener);

    StepExecutionResult result = IsolatedStepsRunner.execute(step, context);

    assertThat(result, equalTo(StepExecutionResults.ERROR));
    String expected =
        "Failed to execute steps"
            + System.lineSeparator()
            + "com.facebook.buck.step.StepFailedException: Command failed with exit code 1."
            + System.lineSeparator()
            + "  When running <test_description>.";
    List<String> actual = listener.getLogMessages();
    assertThat(actual, Matchers.hasSize(1));
    assertThat(actual.get(0), Matchers.containsString(expected));
  }

  @Test
  public void logsErrorIfInterrupted() {
    ImmutableList<IsolatedStep> step =
        ImmutableList.of(
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
            });
    IsolatedExecutionContext context = createContext(projectFilesystem.getRootPath());
    BuckEventBusForTests.CapturingConsoleEventListener listener =
        new BuckEventBusForTests.CapturingConsoleEventListener();
    context.getBuckEventBus().register(listener);

    StepExecutionResult result = IsolatedStepsRunner.execute(step, context);

    assertThat(result, equalTo(StepExecutionResults.ERROR));
    String expected =
        "Received interrupt"
            + System.lineSeparator()
            + "java.lang.InterruptedException: Thread was interrupted inside the executed step: test_short_name";
    List<String> actual = listener.getLogMessages();
    assertThat(actual, Matchers.hasSize(1));
    assertThat(actual.get(0), Matchers.containsString(expected));
  }

  private static IsolatedExecutionContext createContext(AbsPath root) {
    BuckEventBus buckEventBus =
        new DefaultBuckEventBus(
            new DefaultClock(),
            BUILD_UUID_FOR_TEST,
            DefaultBuckEventBus.DEFAULT_SHUTDOWN_TIMEOUT_MS,
            MoreExecutors.newDirectExecutorService());
    Console console = new Console(VERBOSITY_FOR_TEST, System.out, System.err, ANSI_FOR_TEST);
    ProcessExecutor defaultProcessExecutor = new DefaultProcessExecutor(console);

    return IsolatedExecutionContext.of(
        buckEventBus,
        console,
        Platform.detect(),
        defaultProcessExecutor,
        AbsPath.get(root.toString()));
  }
}
