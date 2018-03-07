/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static java.lang.Thread.sleep;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.worker.FakeWorkerProcess;
import com.facebook.buck.worker.WorkerJobParams;
import com.facebook.buck.worker.WorkerJobResult;
import com.facebook.buck.worker.WorkerProcess;
import com.facebook.buck.worker.WorkerProcessIdentity;
import com.facebook.buck.worker.WorkerProcessParams;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.buck.worker.WorkerProcessPoolFactory;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Test;

public class WorkerShellStepTest {

  private static final String startupCommand = "startupCommand";
  private static final String startupArg = "startupArg";
  private static final String persistentStartupArg = "persistentStartupArg";
  private static final String persistentWorkerKey = "//:my-persistent-worker";
  private static final String fakeWorkerStartupCommand =
      String.format("/bin/bash -e -c %s %s", startupCommand, startupArg);
  private static final String fakePersistentWorkerStartupCommand =
      String.format("/bin/bash -e -c %s %s", startupCommand, persistentStartupArg);

  private WorkerShellStep createWorkerShellStep(
      @Nullable WorkerJobParams cmdParams,
      @Nullable WorkerJobParams bashParams,
      @Nullable WorkerJobParams cmdExeParams) {
    return new WorkerShellStep(
        BuildTargetFactory.newInstance("//dummy:target"),
        Optional.ofNullable(cmdParams),
        Optional.ofNullable(bashParams),
        Optional.ofNullable(cmdExeParams),
        new WorkerProcessPoolFactory(new FakeProjectFilesystem()));
  }

  private WorkerJobParams createJobParams() {
    return createJobParams(ImmutableList.of(), ImmutableMap.of(), "");
  }

  private WorkerJobParams createJobParams(
      ImmutableList<String> startupCommand,
      ImmutableMap<String, String> startupEnv,
      String jobArgs) {
    return createJobParams(startupCommand, startupEnv, jobArgs, 1);
  }

  private WorkerJobParams createJobParams(
      ImmutableList<String> startupCommand,
      ImmutableMap<String, String> startupEnv,
      String jobArgs,
      int maxWorkers) {
    return createJobParams(startupCommand, startupEnv, jobArgs, maxWorkers, null, null);
  }

  private WorkerJobParams createJobParams(
      ImmutableList<String> startupCommand,
      ImmutableMap<String, String> startupEnv,
      String jobArgs,
      int maxWorkers,
      @Nullable String persistentWorkerKey,
      @Nullable HashCode workerHash) {
    return WorkerJobParams.of(
        jobArgs,
        WorkerProcessParams.of(
            Paths.get("tmp").toAbsolutePath().normalize(),
            startupCommand,
            startupEnv,
            maxWorkers,
            persistentWorkerKey == null || workerHash == null
                ? Optional.empty()
                : Optional.of(WorkerProcessIdentity.of(persistentWorkerKey, workerHash))));
  }

  private ExecutionContext createExecutionContextWith(int exitCode, String stdout, String stderr) {
    WorkerJobResult jobResult =
        WorkerJobResult.of(exitCode, Optional.of(stdout), Optional.of(stderr));
    return createExecutionContextWith(ImmutableMap.of("myJobArgs", jobResult));
  }

  private ExecutionContext createExecutionContextWith(
      ImmutableMap<String, WorkerJobResult> jobArgs) {
    return createExecutionContextWith(jobArgs, 1);
  }

  private ExecutionContext createExecutionContextWith(
      ImmutableMap<String, WorkerJobResult> jobArgs, int poolCapacity) {
    WorkerProcessPool workerProcessPool =
        new WorkerProcessPool(
            poolCapacity, Hashing.sha1().hashString(fakeWorkerStartupCommand, Charsets.UTF_8)) {
          @Override
          protected WorkerProcess startWorkerProcess() throws IOException {
            return new FakeWorkerProcess(jobArgs);
          }
        };

    ConcurrentHashMap<String, WorkerProcessPool> workerProcessMap = new ConcurrentHashMap<>();
    workerProcessMap.put(fakeWorkerStartupCommand, workerProcessPool);

    WorkerProcessPool persistentWorkerProcessPool =
        new WorkerProcessPool(
            poolCapacity,
            Hashing.sha1().hashString(fakePersistentWorkerStartupCommand, Charsets.UTF_8)) {
          @Override
          protected WorkerProcess startWorkerProcess() throws IOException {
            return new FakeWorkerProcess(jobArgs);
          }
        };
    ConcurrentHashMap<String, WorkerProcessPool> persistentWorkerProcessMap =
        new ConcurrentHashMap<>();
    persistentWorkerProcessMap.put(persistentWorkerKey, persistentWorkerProcessPool);

    ExecutionContext context =
        TestExecutionContext.newBuilder()
            .setPlatform(Platform.LINUX)
            .setWorkerProcessPools(workerProcessMap)
            .setPersistentWorkerPools(persistentWorkerProcessMap)
            .setConsole(new TestConsole(Verbosity.ALL))
            .setBuckEventBus(BuckEventBusForTests.newInstance())
            .build();

    return context;
  }

  @Test
  public void testCmdParamsAreAlwaysUsedIfOthersAreNotSpecified() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerShellStep step = createWorkerShellStep(cmdParams, null, null);
    assertThat(step.getWorkerJobParamsToUse(Platform.WINDOWS), Matchers.sameInstance(cmdParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.LINUX), Matchers.sameInstance(cmdParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.MACOS), Matchers.sameInstance(cmdParams));
  }

  @Test
  public void testBashParamsAreUsedForNonWindowsPlatforms() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerJobParams bashParams = createJobParams();
    WorkerShellStep step = createWorkerShellStep(cmdParams, bashParams, null);
    assertThat(step.getWorkerJobParamsToUse(Platform.WINDOWS), Matchers.sameInstance(cmdParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.LINUX), Matchers.sameInstance(bashParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.MACOS), Matchers.sameInstance(bashParams));
  }

  @Test
  public void testCmdExeParamsAreUsedForWindows() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerJobParams cmdExeParams = createJobParams();
    WorkerShellStep step = createWorkerShellStep(cmdParams, null, cmdExeParams);
    assertThat(step.getWorkerJobParamsToUse(Platform.WINDOWS), Matchers.sameInstance(cmdExeParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.LINUX), Matchers.sameInstance(cmdParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.MACOS), Matchers.sameInstance(cmdParams));
  }

  @Test
  public void testPlatformSpecificParamsArePreferredOverCmdParams() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerJobParams bashParams = createJobParams();
    WorkerJobParams cmdExeParams = createJobParams();
    WorkerShellStep step = createWorkerShellStep(cmdParams, bashParams, cmdExeParams);
    assertThat(step.getWorkerJobParamsToUse(Platform.WINDOWS), Matchers.sameInstance(cmdExeParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.LINUX), Matchers.sameInstance(bashParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.MACOS), Matchers.sameInstance(bashParams));
  }

  @Test(expected = HumanReadableException.class)
  public void testNotSpecifyingParamsThrowsException() {
    WorkerShellStep step = createWorkerShellStep(null, null, null);
    step.getWorkerJobParamsToUse(Platform.LINUX);
  }

  @Test
  public void testGetCommand() {
    WorkerJobParams cmdParams =
        createJobParams(
            ImmutableList.of("command", "--platform", "unix-like"),
            ImmutableMap.of(),
            "job params");
    WorkerJobParams cmdExeParams =
        createJobParams(
            ImmutableList.of("command", "--platform", "windows"), ImmutableMap.of(), "job params");

    WorkerShellStep step = createWorkerShellStep(cmdParams, null, cmdExeParams);
    assertThat(
        step.getFactory().getCommand(Platform.LINUX, cmdParams.getWorkerProcessParams()),
        Matchers.equalTo(
            ImmutableList.of("/bin/bash", "-e", "-c", "command --platform unix-like")));
    assertThat(
        step.getFactory().getCommand(Platform.WINDOWS, cmdExeParams.getWorkerProcessParams()),
        Matchers.equalTo(ImmutableList.of("cmd.exe", "/c", "command --platform windows")));
  }

  @Test
  public void testExpandEnvironmentVariables() {
    WorkerShellStep step = createWorkerShellStep(createJobParams(), null, null);
    assertThat(
        step.expandEnvironmentVariables(
            "the quick brown $FOX jumps over the ${LAZY} dog",
            ImmutableMap.of("FOX", "fox_expanded", "LAZY", "lazy_expanded")),
        Matchers.equalTo("the quick brown fox_expanded jumps over the lazy_expanded dog"));
  }

  @Test
  public void testJobIsExecutedAndResultIsReceived() throws IOException, InterruptedException {
    String stdout = "my stdout";
    String stderr = "my stderr";
    ExecutionContext context = createExecutionContextWith(0, stdout, stderr);
    WorkerShellStep step =
        createWorkerShellStep(
            createJobParams(
                ImmutableList.of(startupCommand, startupArg), ImmutableMap.of(), "myJobArgs"),
            null,
            null);

    FakeBuckEventListener listener = new FakeBuckEventListener();
    context.getBuckEventBus().register(listener);

    int exitCode = step.execute(context).getExitCode();
    assertThat(exitCode, Matchers.equalTo(0));

    // assert that the job's stdout and stderr were written to the console
    BuckEvent firstEvent = listener.getEvents().get(0);
    assertTrue(firstEvent instanceof ConsoleEvent);
    assertThat(((ConsoleEvent) firstEvent).getLevel(), Matchers.is(Level.INFO));
    assertThat(((ConsoleEvent) firstEvent).getMessage(), Matchers.is(stdout));
    BuckEvent secondEvent = listener.getEvents().get(1);
    assertTrue(secondEvent instanceof ConsoleEvent);
    assertThat(((ConsoleEvent) secondEvent).getLevel(), Matchers.is(Level.WARNING));
    assertThat(((ConsoleEvent) secondEvent).getMessage(), Matchers.is(stderr));
  }

  @Test
  public void testPersistentJobIsExecutedAndResultIsReceived()
      throws IOException, InterruptedException {
    ExecutionContext context = createExecutionContextWith(0, "", "");
    WorkerShellStep step =
        createWorkerShellStep(
            createJobParams(
                ImmutableList.of(startupCommand, persistentStartupArg),
                ImmutableMap.of(),
                "myJobArgs",
                1,
                persistentWorkerKey,
                Hashing.sha1().hashString(fakePersistentWorkerStartupCommand, Charsets.UTF_8)),
            null,
            null);

    int exitCode = step.execute(context).getExitCode();
    assertThat(exitCode, Matchers.equalTo(0));
  }

  @Test
  public void testExecuteTwoShellStepsWithSameWorker()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    String jobArgs1 = "jobArgs1";
    String jobArgs2 = "jobArgs2";
    ExecutionContext context =
        createExecutionContextWith(
            ImmutableMap.of(
                jobArgs1, WorkerJobResult.of(0, Optional.of("stdout 1"), Optional.of("stderr 1")),
                jobArgs2, WorkerJobResult.of(0, Optional.of("stdout 2"), Optional.of("stderr 2"))));

    WorkerJobParams params =
        createJobParams(
            ImmutableList.of(startupCommand, startupArg), ImmutableMap.of(), jobArgs1, 1);

    WorkerShellStep step1 = createWorkerShellStep(params, null, null);
    WorkerShellStep step2 = createWorkerShellStep(params.withJobArgs(jobArgs2), null, null);

    step1.execute(context);

    Future<?> stepExecution =
        Executors.newSingleThreadExecutor()
            .submit(
                () -> {
                  try {
                    step2.execute(context);
                  } catch (InterruptedException | IOException e) {
                    throw new RuntimeException(e);
                  }
                });

    stepExecution.get(5, TimeUnit.SECONDS);
  }

  @Test
  public void testStdErrIsPrintedAsErrorIfJobFails() throws IOException, InterruptedException {
    String stderr = "my stderr";
    ExecutionContext context = createExecutionContextWith(1, "", stderr);
    WorkerShellStep step =
        createWorkerShellStep(
            createJobParams(
                ImmutableList.of(startupCommand, startupArg), ImmutableMap.of(), "myJobArgs"),
            null,
            null);

    FakeBuckEventListener listener = new FakeBuckEventListener();
    context.getBuckEventBus().register(listener);

    int exitCode = step.execute(context).getExitCode();
    assertThat(exitCode, Matchers.equalTo(1));

    // assert that the job's stderr was written to the console as error, not as warning
    BuckEvent firstEvent = listener.getEvents().get(0);
    assertTrue(firstEvent instanceof ConsoleEvent);
    assertThat(((ConsoleEvent) firstEvent).getLevel(), Matchers.is(Level.SEVERE));
    assertThat(((ConsoleEvent) firstEvent).getMessage(), Matchers.is(stderr));
    BuckEvent secondEvent = listener.getEvents().get(1);
    assertTrue(secondEvent instanceof ConsoleEvent);
    assertThat(((ConsoleEvent) secondEvent).getLevel(), Matchers.is(Level.INFO));
    assertThat(
        ((ConsoleEvent) secondEvent).getMessage(),
        Matchers.containsString("When building rule //dummy:target"));
  }

  @Test
  public void testGetEnvironmentForProcess() {
    WorkerShellStep step =
        new WorkerShellStep(
            BuildTargetFactory.newInstance("//dummy:target"),
            Optional.of(
                createJobParams(
                    ImmutableList.of(), ImmutableMap.of("BAK", "chicken"), "$FOO $BAR $BAZ $BAK")),
            Optional.empty(),
            Optional.empty(),
            new WorkerProcessPoolFactory(new FakeProjectFilesystem())) {

          @Override
          protected ImmutableMap<String, String> getEnvironmentVariables() {
            return ImmutableMap.of(
                "FOO", "foo_expanded",
                "BAR", "bar_expanded");
          }
        };

    ExecutionContext context =
        TestExecutionContext.newBuilder()
            .setEnvironment(
                ImmutableMap.of(
                    "BAR", "this should be ignored for substitution",
                    "BAZ", "baz_expanded"))
            .build();

    Map<String, String> processEnv =
        new HashMap<>(
            step.getFactory()
                .getEnvironmentForProcess(
                    context,
                    step.getWorkerJobParamsToUse(Platform.UNKNOWN).getWorkerProcessParams()));
    processEnv.remove("TMP");
    assertThat(
        processEnv,
        Matchers.equalTo(
            ImmutableMap.of(
                "BAR", "this should be ignored for substitution",
                "BAZ", "baz_expanded",
                "BAK", "chicken")));
    assertThat(
        step.getExpandedJobArgs(context), Matchers.equalTo("foo_expanded bar_expanded $BAZ $BAK"));
  }

  @Test
  public void testMultipleWorkerProcesses() throws InterruptedException {
    String jobArgsA = "jobArgsA";
    String jobArgsB = "jobArgsB";
    ImmutableMap<String, WorkerJobResult> jobResults =
        ImmutableMap.of(
            jobArgsA, WorkerJobResult.of(0, Optional.of("stdout A"), Optional.of("stderr A")),
            jobArgsB, WorkerJobResult.of(0, Optional.of("stdout B"), Optional.of("stderr B")));

    class WorkerShellStepWithFakeProcesses extends WorkerShellStep {
      WorkerShellStepWithFakeProcesses(WorkerJobParams jobParams) {
        super(
            BuildTargetFactory.newInstance("//dummy:target"),
            Optional.ofNullable(jobParams),
            Optional.empty(),
            Optional.empty(),
            new WorkerProcessPoolFactory(new FakeProjectFilesystem()) {
              @Override
              public WorkerProcess createWorkerProcess(
                  ProcessExecutorParams processParams, ExecutionContext context, Path tmpDir)
                  throws IOException {
                try {
                  sleep(5);
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                return new FakeWorkerProcess(jobResults);
              }
            });
      }
    }

    ExecutionContext context =
        TestExecutionContext.newBuilder()
            .setPlatform(Platform.LINUX)
            .setConsole(new TestConsole(Verbosity.ALL))
            .setBuckEventBus(BuckEventBusForTests.newInstance())
            .build();

    WorkerJobParams jobParamsA =
        createJobParams(
            ImmutableList.of(startupCommand, startupArg), ImmutableMap.of(), jobArgsA, 2);
    WorkerShellStep stepA = new WorkerShellStepWithFakeProcesses(jobParamsA);
    WorkerShellStep stepB = new WorkerShellStepWithFakeProcesses(jobParamsA.withJobArgs(jobArgsB));

    Thread[] threads = {
      new ConcurrentExecution(stepA, context), new ConcurrentExecution(stepB, context),
    };

    for (Thread t : threads) {
      t.start();
    }

    for (Thread t : threads) {
      t.join();
    }

    Collection<WorkerProcessPool> pools = context.getWorkerProcessPools().values();
    assertThat(pools.size(), Matchers.equalTo(1));

    WorkerProcessPool pool = pools.iterator().next();
    assertThat(pool.getCapacity(), Matchers.equalTo(2));
  }

  @Test
  public void testWarningIsPrintedForIdenticalWorkerToolsWithDifferentCapacity() throws Exception {
    int existingPoolSize = 2;
    int stepPoolSize = 4;

    ExecutionContext context =
        createExecutionContextWith(
            ImmutableMap.of("jobArgs", WorkerJobResult.of(0, Optional.of(""), Optional.of(""))),
            existingPoolSize);

    FakeBuckEventListener listener = new FakeBuckEventListener();
    context.getBuckEventBus().register(listener);

    WorkerJobParams params =
        createJobParams(
            ImmutableList.of(startupCommand, startupArg),
            ImmutableMap.of(),
            "jobArgs",
            stepPoolSize);

    WorkerShellStep step = createWorkerShellStep(params, null, null);
    step.execute(context);

    BuckEvent firstEvent = listener.getEvents().get(0);
    assertThat(firstEvent, Matchers.instanceOf(ConsoleEvent.class));

    ConsoleEvent consoleEvent = (ConsoleEvent) firstEvent;
    assertThat(consoleEvent.getLevel(), Matchers.is(Level.WARNING));
    assertThat(
        consoleEvent.getMessage(),
        Matchers.is(
            String.format(
                "There are two 'worker_tool' targets declared with the same command (%s), but different "
                    + "'max_worker' settings (%d and %d). Only the first capacity is applied. Consolidate "
                    + "these workers to avoid this warning.",
                fakeWorkerStartupCommand, existingPoolSize, stepPoolSize)));
  }

  private static class ConcurrentExecution extends Thread {
    private final WorkerShellStep step;
    private final ExecutionContext context;

    ConcurrentExecution(WorkerShellStep step, ExecutionContext context) {
      this.step = step;
      this.context = context;
    }

    @Override
    public void run() {
      try {
        step.execute(context);
      } catch (InterruptedException | IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
