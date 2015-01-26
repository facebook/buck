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

import com.facebook.buck.util.TriState;
import com.google.common.base.Supplier;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import java.io.IOException;

public class ConditionalStepTest extends EasyMockSupport {

  @Test
  public void testExecuteConditionalStepWhenTrue() throws IOException, InterruptedException {
    // Create a Supplier<Boolean> that returns a value once an external condition has been
    // satisfied.
    final AtomicReference<TriState> condition = new AtomicReference<>(
        TriState.UNSPECIFIED);
    Supplier<Boolean> conditional = new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        if (!condition.get().isSet()) {
          throw new IllegalStateException("condition must be satisfied before this is queried.");
        } else {
          return condition.get().asBoolean();
        }
      }
    };

    // Create a step to run when the Supplier<Boolean> is true. Exit code is always 37.
    final AtomicInteger numCalls = new AtomicInteger(0);
    Step stepToRunWhenSupplierIsTrue = new AbstractExecutionStep("inc") {
      @Override
      public int execute(ExecutionContext context) {
        numCalls.incrementAndGet();
        return 37;
      }
    };

    // Create and execute the ConditionalStep.
    ConditionalStep conditionalStep = new ConditionalStep(conditional, stepToRunWhenSupplierIsTrue);
    condition.set(TriState.TRUE);
    ExecutionContext context = TestExecutionContext.newInstance();
    int exitCode = conditionalStep.execute(context);
    assertEquals("stepToRunWhenSupplierIsTrue should have been run once.", 1, numCalls.get());
    assertEquals("The exit code of stepToRunWhenSupplierIsTrue should be passed through.",
        37,
        exitCode);
    assertEquals("inc", conditionalStep.getShortName());
    assertEquals("conditionally: inc", conditionalStep.getDescription(context));
  }

  @Test
  public void testExecuteConditionalStepWhenFalse() throws IOException, InterruptedException {
    // Create a Supplier<Boolean> that returns a value once an external condition has been
    // satisfied.
    final AtomicReference<TriState> condition = new AtomicReference<>(
        TriState.UNSPECIFIED);
    Supplier<Boolean> conditional = new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        if (!condition.get().isSet()) {
          throw new IllegalStateException("condition must be satisfied before this is queried.");
        } else {
          return condition.get().asBoolean();
        }
      }
    };

    // Create a step to run when the Supplier<Boolean> is true. Because the Supplier<Boolean> will
    // be false, this step should not be run.
    final AtomicInteger numCalls = new AtomicInteger(0);
    Step stepToRunWhenSupplierIsTrue = new AbstractExecutionStep("inc") {
      @Override
      public int execute(ExecutionContext context) {
        numCalls.incrementAndGet();
        throw new IllegalStateException("This step should not be executed.");
      }
    };

    // Create and execute the ConditionalStep.
    ConditionalStep conditionalStep = new ConditionalStep(conditional, stepToRunWhenSupplierIsTrue);
    condition.set(TriState.FALSE);
    ExecutionContext context = TestExecutionContext.newInstance();
    int exitCode = conditionalStep.execute(context);
    assertEquals("stepToRunWhenSupplierIsTrue should not have been run.", 0, numCalls.get());
    assertEquals("The exit code should be zero when the conditional is false.", 0, exitCode);
    assertEquals("inc", conditionalStep.getShortName());
    assertEquals("conditionally: inc", conditionalStep.getDescription(context));
  }
}
