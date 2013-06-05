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

import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class DefaultStepRunnerTest {

  @Test(expected=StepFailedException.class, timeout=1000)
  public void testParallelCommandFailure() throws Exception {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    commands.add(new SleepingStep(0, 0));
    commands.add(new SleepingStep(10, 1));
    // Add a command that will also fail, but taking longer than the test timeout to complete.
    // This tests the fail-fast behaviour of runCommandsInParallelAndWait (that is, since the 10ms
    // command will fail so quickly, the result of the 5000ms command will not be observed).
    commands.add(new SleepingStep(5000, 1));

    ExecutionContext context = new ExecutionContext(
        Verbosity.SILENT,
        EasyMock.createMock(ProjectFilesystem.class),
        new Console(System.out, System.err, Ansi.withoutTty()),
        Optional.<AndroidPlatformTarget>absent(),
        Optional.<File>absent(),
        false,
        false);
    ThreadFactory threadFactory = new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat(getClass().getSimpleName() + "-%d")
        .build();
    ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(3, threadFactory));
    DefaultStepRunner runner = new DefaultStepRunner(context, executorService);
    runner.runCommandsInParallelAndWait(commands.build());

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
