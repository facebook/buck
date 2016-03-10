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

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import javax.annotation.Nullable;

public class WorkerShellStepTest {

  private WorkerShellStep createXargsShellStep(
      @Nullable WorkerJobParams cmdParams,
      @Nullable WorkerJobParams bashParams,
      @Nullable WorkerJobParams cmdExeParams) {
    return new WorkerShellStep(
        new FakeProjectFilesystem(),
        Paths.get("tmp").toAbsolutePath().normalize(),
        Paths.get(".").toAbsolutePath().normalize(),
        Optional.fromNullable(cmdParams),
        Optional.fromNullable(bashParams),
        Optional.fromNullable(cmdExeParams));
  }

  private WorkerJobParams createJobParams() {
    return createJobParams(
        ImmutableList.<String>of(),
        "",
        "");
  }

  private WorkerJobParams createJobParams(
      ImmutableList<String> startupCommand,
      String startupArgs,
      String jobArgs) {
    return WorkerJobParams.of(startupCommand, startupArgs, jobArgs);
  }

  @Test
  public void testCmdParamsAreAlwaysUsedIfOthersAreNotSpecified() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerShellStep step = createXargsShellStep(cmdParams, null, null);
    assertThat(
        step.getWorkerJobParamsToUse(Platform.WINDOWS),
        Matchers.sameInstance(cmdParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.LINUX), Matchers.sameInstance(cmdParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.MACOS), Matchers.sameInstance(cmdParams));
  }

  @Test
  public void testBashParamsAreUsedForNonWindowsPlatforms() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerJobParams bashParams = createJobParams();
    WorkerShellStep step = createXargsShellStep(cmdParams, bashParams, null);
    assertThat(
        step.getWorkerJobParamsToUse(Platform.WINDOWS),
        Matchers.sameInstance(cmdParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.LINUX),
        Matchers.sameInstance(bashParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.MACOS),
        Matchers.sameInstance(bashParams));
  }

  @Test
  public void testCmdExeParamsAreUsedForWindows() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerJobParams cmdExeParams = createJobParams();
    WorkerShellStep step = createXargsShellStep(cmdParams, null, cmdExeParams);
    assertThat(
        step.getWorkerJobParamsToUse(Platform.WINDOWS),
        Matchers.sameInstance(cmdExeParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.LINUX),
        Matchers.sameInstance(cmdParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.MACOS),
        Matchers.sameInstance(cmdParams));
  }

  @Test
  public void testPlatformSpecificParamsArePreferredOverCmdParams() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerJobParams bashParams = createJobParams();
    WorkerJobParams cmdExeParams = createJobParams();
    WorkerShellStep step = createXargsShellStep(cmdParams, bashParams, cmdExeParams);
    assertThat(
        step.getWorkerJobParamsToUse(Platform.WINDOWS),
        Matchers.sameInstance(cmdExeParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.LINUX),
        Matchers.sameInstance(bashParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.MACOS),
        Matchers.sameInstance(bashParams));
  }

  @Test(expected = HumanReadableException.class)
  public void testNotSpecifyingParamsThrowsException() {
    WorkerShellStep step = createXargsShellStep(null, null, null);
    step.getWorkerJobParamsToUse(Platform.LINUX);
  }

  @Test
  public void testGetCommand() {
    WorkerJobParams cmdParams = createJobParams(
        ImmutableList.of("command"),
        "--platform unix-like",
        "job params");
    WorkerJobParams cmdExeParams = createJobParams(
        ImmutableList.of("command"),
        "--platform windows",
        "job params");

    WorkerShellStep step = createXargsShellStep(cmdParams, null, cmdExeParams);
    assertThat(
        step.getCommand(Platform.LINUX),
        Matchers.equalTo(
            ImmutableList.of(
                "/bin/bash",
                "-e",
                "command",
                "--platform",
                "unix-like")));
    assertThat(
        step.getCommand(Platform.WINDOWS),
        Matchers.equalTo(
            ImmutableList.of(
                "cmd.exe",
                "/c",
                "command",
                "--platform",
                "windows")));
  }

  @Test
  public void testExpandEnvironmentVariables() {
    WorkerShellStep step = createXargsShellStep(createJobParams(), null, null);
    assertThat(
        step.expandEnvironmentVariables(
            "the quick brown $FOX jumps over the ${LAZY} dog",
            ImmutableMap.of("FOX", "fox_expanded", "LAZY", "lazy_expanded")),
        Matchers.equalTo("the quick brown fox_expanded jumps over the lazy_expanded dog"));
  }

  @Test
  public void testJobIsExecutedAndResultIsReceived()
      throws IOException, InterruptedException {
    WorkerShellStep step = createXargsShellStep(
        createJobParams(
            ImmutableList.of("startupCommand"),
            "startupArgs",
            "myJobArgs"),
        null,
        null);

    WorkerJobResult jobResult = WorkerJobResult.of(
        0,
        Optional.of("my stdout"),
        Optional.of("my stderr"));
    WorkerProcess workerProcess = new FakeWorkerProcess(ImmutableMap.of("myJobArgs", jobResult));

    ConcurrentHashMap<String, WorkerProcess> workerProcessMap = new ConcurrentHashMap<>();
    workerProcessMap.put("/bin/bash -e startupCommand startupArgs", workerProcess);

    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    FakeBuckEventListener listener = new FakeBuckEventListener();
    eventBus.register(listener);

    Console console = new TestConsole(Verbosity.ALL);
    ExecutionContext context = TestExecutionContext
        .newBuilder()
        .setPlatform(Platform.LINUX)
        .setWorkerProcesses(workerProcessMap)
        .setConsole(console)
        .setEventBus(eventBus)
        .build();

    int exitCode = step.execute(context);
    assertThat(exitCode, Matchers.equalTo(0));

    // assert that the job's stdout and stderr were written to the console
    BuckEvent firstEvent = listener.getEvents().get(0);
    assertTrue(firstEvent instanceof ConsoleEvent);
    assertThat(((ConsoleEvent) firstEvent).getLevel(), Matchers.is(Level.INFO));
    assertThat(((ConsoleEvent) firstEvent).getMessage(), Matchers.is("my stdout"));
    BuckEvent secondEvent = listener.getEvents().get(1);
    assertTrue(secondEvent instanceof ConsoleEvent);
    assertThat(((ConsoleEvent) secondEvent).getLevel(), Matchers.is(Level.WARNING));
    assertThat(((ConsoleEvent) secondEvent).getMessage(), Matchers.is("my stderr"));
  }

  @Test
  public void testGetEnvironmentForProcess() {
    WorkerShellStep step = new WorkerShellStep(
        new FakeProjectFilesystem(),
        Paths.get("tmp").toAbsolutePath().normalize(),
        Paths.get(".").toAbsolutePath().normalize(),
        Optional.of(createJobParams()),
        Optional.of(createJobParams()),
        Optional.of(createJobParams())) {

      @Override
      protected ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
        return ImmutableMap.of(
            "FOO", "foo_expanded",
            "BAR", "bar_expanded");
      }
    };

    ExecutionContext context = TestExecutionContext
        .newBuilder()
        .setEnvironment(
            ImmutableMap.of(
                "BAR", "this should be ignored because the step overrides the same variable",
                "BAZ", "baz_expanded"))
        .build();

    assertThat(
        step.getEnvironmentForProcess(context),
        Matchers.equalTo(
            ImmutableMap.of(
                "FOO", "foo_expanded",
                "BAR", "bar_expanded",
                "BAZ", "baz_expanded")));
  }
}
