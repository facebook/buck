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

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.easymock.EasyMockSupport;
import org.junit.Test;

public class ConditionalStepTest extends EasyMockSupport {

  @Test
  public void testExecuteConditionalStepWhenTrue() throws IOException, InterruptedException {
    // Create a Supplier<Boolean> that returns a value once an external condition has been
    // satisfied.
    AtomicReference<Optional<Boolean>> condition = new AtomicReference<>(Optional.empty());
    Supplier<Boolean> conditional =
        () -> {
          if (!condition.get().isPresent()) {
            throw new IllegalStateException("condition must be satisfied before this is queried.");
          } else {
            return condition.get().get();
          }
        };

    // Create a step to run when the Supplier<Boolean> is true. Exit code is always 37.
    AtomicInteger numCalls = new AtomicInteger(0);
    Step stepToRunWhenSupplierIsTrue =
        new AbstractExecutionStep("inc") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) {
            numCalls.incrementAndGet();
            return StepExecutionResult.of(37);
          }
        };

    // Create and execute the ConditionalStep.
    ConditionalStep conditionalStep = new ConditionalStep(conditional, stepToRunWhenSupplierIsTrue);
    condition.set(Optional.of(true));
    ExecutionContext context = TestExecutionContext.newInstance();
    int exitCode = conditionalStep.execute(context).getExitCode();
    assertEquals("stepToRunWhenSupplierIsTrue should have been run once.", 1, numCalls.get());
    assertEquals(
        "The exit code of stepToRunWhenSupplierIsTrue should be passed through.", 37, exitCode);
    assertEquals("inc", conditionalStep.getShortName());
    assertEquals("conditionally: inc", conditionalStep.getDescription(context));
  }

  @Test
  public void testExecuteConditionalStepWhenFalse() throws IOException, InterruptedException {
    // Create a Supplier<Boolean> that returns a value once an external condition has been
    // satisfied.
    AtomicReference<Optional<Boolean>> condition = new AtomicReference<>(Optional.empty());
    Supplier<Boolean> conditional =
        () -> {
          if (!condition.get().isPresent()) {
            throw new IllegalStateException("condition must be satisfied before this is queried.");
          } else {
            return condition.get().get();
          }
        };

    // Create a step to run when the Supplier<Boolean> is true. Because the Supplier<Boolean> will
    // be false, this step should not be run.
    AtomicInteger numCalls = new AtomicInteger(0);
    Step stepToRunWhenSupplierIsTrue =
        new AbstractExecutionStep("inc") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) {
            numCalls.incrementAndGet();
            throw new IllegalStateException("This step should not be executed.");
          }
        };

    // Create and execute the ConditionalStep.
    ConditionalStep conditionalStep = new ConditionalStep(conditional, stepToRunWhenSupplierIsTrue);
    condition.set(Optional.of(false));
    ExecutionContext context = TestExecutionContext.newInstance();
    int exitCode = conditionalStep.execute(context).getExitCode();
    assertEquals("stepToRunWhenSupplierIsTrue should not have been run.", 0, numCalls.get());
    assertEquals("The exit code should be zero when the conditional is false.", 0, exitCode);
    assertEquals("inc", conditionalStep.getShortName());
    assertEquals("conditionally: inc", conditionalStep.getDescription(context));
  }
}
