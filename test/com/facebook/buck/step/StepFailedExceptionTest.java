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

package com.facebook.buck.step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class StepFailedExceptionTest {

  private ExecutionContext verboseContext;
  private ExecutionContext silentContext;

  @Before
  public void setUp() {
    ExecutionContext context = TestExecutionContext.newInstance();
    verboseContext = context.withConsole(new TestConsole(Verbosity.ALL));
    silentContext = context.withConsole(new TestConsole(Verbosity.SILENT));
  }

  @Test
  public void testCreateForFailingStepForExitCodeWithBuildTarget() {
    int exitCode = 17;
    StepExecutionResult executionResult = StepExecutionResult.of(exitCode);
    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    StepFailedException exception =
        StepFailedException.createForFailingStepWithExitCode(step, verboseContext, executionResult);

    assertEquals(step, exception.getStep());
    assertEquals(
        "Command failed with exit code 17."
            + System.lineSeparator()
            + "  When running <cp foo bar>.",
        exception.getMessage());
  }

  @Test
  public void testCreateForFailingStepForExitCodeWithoutBuildTarget() {
    int exitCode = 17;
    StepExecutionResult executionResult = StepExecutionResult.of(exitCode);
    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    StepFailedException exception =
        StepFailedException.createForFailingStepWithExitCode(step, verboseContext, executionResult);

    assertEquals(step, exception.getStep());
    assertEquals(
        "Command failed with exit code 17."
            + System.lineSeparator()
            + "  When running <cp foo bar>.",
        exception.getMessage());
  }

  @Test
  public void testCreateForFailingStepWithSilentConsole() {
    int exitCode = 17;
    StepExecutionResult executionResult = StepExecutionResult.of(exitCode);
    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    StepFailedException exception =
        StepFailedException.createForFailingStepWithExitCode(step, silentContext, executionResult);

    assertEquals(step, exception.getStep());
    assertEquals(
        "Command failed with exit code 17." + System.lineSeparator() + "  When running <cp>.",
        exception.getMessage());
  }

  @Test
  public void testCreateForFailingStepWithBuildTarget() {
    int exitCode = 17;
    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    StepFailedException exception =
        StepFailedException.createForFailingStepWithException(
            step, silentContext, new IOException("Copy failed!"));

    assertEquals(step, exception.getStep());
    assertTrue(
        exception.getMessage(),
        exception
            .getMessage()
            .startsWith("Copy failed!" + System.lineSeparator() + "  When running <cp>."));
  }

  @Test
  public void testCreateForFailingStepWithoutBuildTarget() {
    int exitCode = 17;
    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    StepFailedException exception =
        StepFailedException.createForFailingStepWithException(
            step, silentContext, new IOException("Copy failed!"));

    assertEquals(step, exception.getStep());
    assertTrue(
        exception.getMessage(),
        exception
            .getMessage()
            .startsWith("Copy failed!" + System.lineSeparator() + "  When running <cp>."));
  }

  @Test
  public void testCreateForFailingStepWithTruncation() {
    int exitCode = 17;

    Step step = new FakeStep("cp", "cp foo bar", exitCode);

    List<String> testCommand = new ArrayList<>();
    for (int i = 0; i < StepFailedException.KEEP_FIRST_CHARS; i++) {
      testCommand.add("ab");
    }
    ProcessExecutor.Result result =
        new ProcessExecutor.Result(
            StepExecutionResults.ERROR_EXIT_CODE,
            ImmutableList.<String>builder().addAll(testCommand).build());
    StepExecutionResult executionResult = StepExecutionResult.of(result);

    assertTrue(silentContext.isTruncateFailingCommandEnabled()); // truncation is enabled by default

    StepFailedException exception =
        StepFailedException.createForFailingStepWithExitCode(step, silentContext, executionResult);

    assertEquals(step, exception.getStep());
    assertFalse(executionResult.getExecutedCommand().isEmpty());
    assertTrue(exception.getMessage(), exception.getMessage().contains("<truncated>"));
  }

  @Test
  public void testCreateForFailingStepWithoutTruncation() {
    int exitCode = 17;

    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    List<String> testCommand = new ArrayList<>();
    for (int i = 0; i < StepFailedException.KEEP_FIRST_CHARS; i++) {
      testCommand.add("ab");
    }
    ProcessExecutor.Result result =
        new ProcessExecutor.Result(
            StepExecutionResults.ERROR_EXIT_CODE,
            ImmutableList.<String>builder().addAll(testCommand).build());

    StepExecutionResult executionResult = StepExecutionResult.of(result);

    ExecutionContext context =
        TestExecutionContext.newBuilder().setTruncateFailingCommandEnabled(false).build();

    StepFailedException exception =
        StepFailedException.createForFailingStepWithExitCode(step, context, executionResult);

    assertEquals(step, exception.getStep());
    assertFalse(executionResult.getExecutedCommand().isEmpty());
    assertFalse(exception.getMessage(), exception.getMessage().contains("<truncated>"));
  }
}
