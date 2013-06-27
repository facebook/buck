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

import static org.easymock.EasyMock.createMock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;

import org.junit.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class DefaultStepRunnerTest {

  @Test
  public void testEventsFired() throws StepFailedException {
    Step passingStep = new FakeStep("step1", "fake step 1", 0);
    Step failingStep = new FakeStep("step1", "fake step 1", 1);

    // The EventBus should be updated with events indicating how the steps were run.
    EventBus eventBus = new EventBus();
    BuckEventListener listener = new BuckEventListener();
    eventBus.register(listener);

    BuckEventBus buckEventBus = new BuckEventBus(eventBus);

    ExecutionContext context = ExecutionContext.builder()
        .setProjectFilesystem(createMock(ProjectFilesystem.class))
        .setConsole(new TestConsole())
        .setEventBus(buckEventBus)
        .build();

    ThreadFactory threadFactory = new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat(getClass().getSimpleName() + "-%d")
        .build();
    ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(3, threadFactory));
    DefaultStepRunner runner = new DefaultStepRunner(context, executorService);
    runner.runStep(passingStep);
    try {
      runner.runStep(failingStep);
      fail("Failing step should have thrown an exception");
    } catch (StepFailedException e) {
      assertEquals(e.getStep(), failingStep);
    }

    ImmutableList<StepEvent> expected = ImmutableList.of(
        StepEvent.started(passingStep, "step1", "fake step 1"),
        StepEvent.finished(passingStep, "step1", "fake step 1", 0),
        StepEvent.started(failingStep, "step1", "fake step 1"),
        StepEvent.finished(failingStep, "step1", "fake step 1", 1));

    Iterable<StepEvent> events = Iterables.filter(listener.getEvents(), StepEvent.class);
    assertEquals(expected, ImmutableList.copyOf(events));
  }

  @Test(expected=StepFailedException.class, timeout=5000)
  public void testParallelStepFailure() throws Exception {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new SleepingStep(0, 0));
    steps.add(new SleepingStep(10, 1));
    // Add a step that will also fail, but taking longer than the test timeout to complete.
    // This tests the fail-fast behaviour of runStepsInParallelAndWait (that is, since the 10ms
    // step will fail so quickly, the result of the 5000ms step will not be observed).
    steps.add(new SleepingStep(5000, 1));

    ExecutionContext context = ExecutionContext.builder()
        .setProjectFilesystem(createMock(ProjectFilesystem.class))
        .setConsole(new TestConsole())
        .setEventBus(new BuckEventBus())
        .build();
    ThreadFactory threadFactory = new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat(getClass().getSimpleName() + "-%d")
        .build();
    ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(3, threadFactory));
    DefaultStepRunner runner = new DefaultStepRunner(context, executorService);
    runner.runStepsInParallelAndWait(steps.build());

    // Success if the test timeout is not reached.
  }

  private static class SleepingStep implements Step {
    private final long sleepMillis;
    private final int exitCode;

    public SleepingStep(long sleepMillis, int exitCode) {
      this.sleepMillis = sleepMillis;
      this.exitCode = exitCode;
    }

    @Override
    public int execute(ExecutionContext context) {
      Uninterruptibles.sleepUninterruptibly(sleepMillis, TimeUnit.MILLISECONDS);
      return exitCode;
    }

    @Override
    public String getShortName(ExecutionContext context) {
      return "sleep";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return String.format("%s %d, then %s",
          getShortName(context),
          sleepMillis,
          exitCode == 0 ? "success" : "fail"
      );
    }
  }
}