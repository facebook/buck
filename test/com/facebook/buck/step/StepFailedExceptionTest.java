/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Verbosity;
import java.io.IOException;
import java.util.Optional;
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
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    StepFailedException exception =
        StepFailedException.createForFailingStepWithExitCode(
            step, verboseContext, executionResult, Optional.of(buildTarget));

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
        StepFailedException.createForFailingStepWithExitCode(
            step, verboseContext, executionResult, Optional.empty());

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
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    StepFailedException exception =
        StepFailedException.createForFailingStepWithExitCode(
            step, silentContext, executionResult, Optional.of(buildTarget));

    assertEquals(step, exception.getStep());
    assertEquals(
        "Command failed with exit code 17." + System.lineSeparator() + "  When running <cp>.",
        exception.getMessage());
  }

  @Test
  public void testCreateForFailingStepWithBuildTarget() {
    int exitCode = 17;
    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    StepFailedException exception =
        StepFailedException.createForFailingStepWithException(
            step, silentContext, new IOException("Copy failed!"), Optional.of(buildTarget));

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
            step, silentContext, new IOException("Copy failed!"), Optional.empty());

    assertEquals(step, exception.getStep());
    assertTrue(
        exception.getMessage(),
        exception
            .getMessage()
            .startsWith("Copy failed!" + System.lineSeparator() + "  When running <cp>."));
  }
}
