/*
 * Copyright 2012-present Facebook, Inc.
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
import static org.junit.Assert.fail;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.event.TestEventConfigerator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.Executors;

public class DefaultStepRunnerTest {

  @Test
  public void testEventsFired() throws StepFailedException, InterruptedException, IOException {
    Step passingStep = new FakeStep("step1", "fake step 1", 0);
    Step failingStep = new FakeStep("step1", "fake step 1", 1);

    // The EventBus should be updated with events indicating how the steps were run.
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    eventBus.register(listener);

    ExecutionContext context = TestExecutionContext.newBuilder()
        .setEventBus(eventBus)
        .build();
    DefaultStepRunner runner =
        new DefaultStepRunner(context, MoreExecutors.newDirectExecutorService());
    runner.runStep(passingStep);
    try {
      runner.runStep(failingStep);
      fail("Failing step should have thrown an exception");
    } catch (StepFailedException e) {
      assertEquals(e.getStep(), failingStep);
    }

    ImmutableList<StepEvent> expected = ImmutableList.of(
        TestEventConfigerator.configureTestEvent(
            StepEvent.started(passingStep, "fake step 1"), eventBus),
        TestEventConfigerator.configureTestEvent(
            StepEvent.finished(passingStep, "fake step 1", 0), eventBus),
        TestEventConfigerator.configureTestEvent(
            StepEvent.started(failingStep, "fake step 1"), eventBus),
        TestEventConfigerator.configureTestEvent(
            StepEvent.finished(failingStep, "fake step 1", 1), eventBus));

    Iterable<StepEvent> events = Iterables.filter(listener.getEvents(), StepEvent.class);
    assertEquals(expected, ImmutableList.copyOf(events));
  }

  @Test(expected = StepFailedException.class, timeout = 5000)
  public void testParallelStepFailure()
      throws StepFailedException, InterruptedException, IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new SleepingStep(0, 0));
    steps.add(new SleepingStep(10, 1));
    // Add a step that will also fail, but taking longer than the test timeout to complete.
    // This tests the fail-fast behaviour of runStepsInParallelAndWait (that is, since the 10ms
    // step will fail so quickly, the result of the 5000ms step will not be observed).
    steps.add(new SleepingStep(5000, 1));

    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
    DefaultStepRunner runner = new DefaultStepRunner(TestExecutionContext.newInstance(), service);
    runner.runStepsInParallelAndWait(steps.build());

    // Success if the test timeout is not reached.
  }

  @Test
  public void testExplodingStep() throws InterruptedException, IOException {
    ExecutionContext context = TestExecutionContext.newInstance();

    DefaultStepRunner runner =
        new DefaultStepRunner(context, MoreExecutors.newDirectExecutorService());
    try {
      runner.runStep(new ExplosionStep());
      fail("Should have thrown a StepFailedException!");
    } catch (StepFailedException e) {
      assertTrue(e.getMessage().startsWith("Failed on step explode with an exception:\n#yolo"));
    }
  }

  private static class ExplosionStep implements Step {
    @Override
    public int execute(ExecutionContext context) {
      throw new RuntimeException("#yolo");
    }

    @Override
    public String getShortName() {
      return "explode";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return "MOAR EXPLOSIONS!!!!";
    }
  }

  private static class SleepingStep implements Step {
    private final long sleepMillis;
    private final int exitCode;

    public SleepingStep(long sleepMillis, int exitCode) {
      this.sleepMillis = sleepMillis;
      this.exitCode = exitCode;
    }

    @Override
    public int execute(ExecutionContext context) throws InterruptedException {
      Thread.sleep(sleepMillis);
      return exitCode;
    }

    @Override
    public String getShortName() {
      return "sleep";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return String.format("%s %d, then %s",
          getShortName(),
          sleepMillis,
          exitCode == 0 ? "success" : "fail");
    }
  }
}
