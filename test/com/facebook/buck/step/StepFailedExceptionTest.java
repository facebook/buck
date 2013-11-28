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

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Optional;

import org.junit.Test;

import java.io.IOException;

public class StepFailedExceptionTest {

  @Test
  public void testCreateForFailingStepForExitCodeWithBuildTarget() {
    final int exitCode = 17;
    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    ExecutionContext context = TestExecutionContext.newInstance();
    BuildTarget buildTarget = new BuildTarget("//foo", "bar");
    StepFailedException exception = StepFailedException.createForFailingStepWithExitCode(
        step, context, exitCode, Optional.of(buildTarget));

    assertEquals(step, exception.getStep());
    assertEquals(exitCode, exception.getExitCode());
    assertEquals("//foo:bar failed with exit code 17:\ncp foo bar", exception.getMessage());
  }

  @Test
  public void testCreateForFailingStepForExitCodeWithoutBuildTarget() {
    final int exitCode = 17;
    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    ExecutionContext context = TestExecutionContext.newInstance();
    StepFailedException exception = StepFailedException.createForFailingStepWithExitCode(
        step, context, exitCode, Optional.<BuildTarget>absent());

    assertEquals(step, exception.getStep());
    assertEquals(exitCode, exception.getExitCode());
    assertEquals("Failed with exit code 17:\ncp foo bar", exception.getMessage());
  }

  @Test
  public void testCreateForFailingStepWithBuildTarget() {
    final int exitCode = 17;
    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    BuildTarget buildTarget = new BuildTarget("//foo", "bar");
    StepFailedException exception = StepFailedException.createForFailingStepWithException(
        step, new IOException("Copy failed!"), Optional.of(buildTarget));

    assertEquals(step, exception.getStep());
    assertEquals(1, exception.getExitCode());
    assertEquals("//foo:bar failed on step cp with an exception:\nCopy failed!",
        exception.getMessage());
  }

  @Test
  public void testCreateForFailingStepWithoutBuildTarget() {
    final int exitCode = 17;
    Step step = new FakeStep("cp", "cp foo bar", exitCode);
    StepFailedException exception = StepFailedException.createForFailingStepWithException(
        step, new IOException("Copy failed!"), Optional.<BuildTarget>absent());

    assertEquals(step, exception.getStep());
    assertEquals(1, exception.getExitCode());
    assertEquals("Failed on step cp with an exception:\nCopy failed!",
        exception.getMessage());
  }
}
