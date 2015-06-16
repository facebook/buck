/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple.simulator;

import static org.junit.Assert.assertThat;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Map;

import java.util.AbstractMap.SimpleImmutableEntry;

import org.junit.Test;

/**
 * Unit tests for {@link AppleSimulatorController}.
 */
public class AppleSimulatorControllerTest {
  private static final Path SIMCTL_PATH = Paths.get("path/to/simctl");
  private static final Path IOS_SIMULATOR_PATH = Paths.get("path/to/iOS Simulator.app");
  private static final ProcessExecutorParams SIMCTL_LIST_PARAMS =
      ProcessExecutorParams.builder()
          .setCommand(
              ImmutableList.of(
                  SIMCTL_PATH.toString(),
                  "list"))
          .build();

  @Test
  public void canStartSimulatorWhenNoSimulatorBooted()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            SIMCTL_LIST_PARAMS,
            new FakeProcess(
                0,
                "    iPhone 5 (45BD7164-686C-474F-8C68-3730432BC5F2) (Shutdown)\n" +
                "    iPhone 5s (70200ED8-EEF1-4BDB-BCCF-3595B137D67D) (Shutdown)\n",
                "")));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        fakeProcessesBuilder.build());
    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        fakeProcessExecutor,
        SIMCTL_PATH,
        IOS_SIMULATOR_PATH);

    assertThat(
        appleSimulatorController.canStartSimulator("45BD7164-686C-474F-8C68-3730432BC5F2"),
        is(true));
  }

  @Test
  public void canStartSimulatorWhenSimulatorAlreadyBooted()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            SIMCTL_LIST_PARAMS,
            new FakeProcess(
                0,
                "    iPhone 5 (45BD7164-686C-474F-8C68-3730432BC5F2) (Booted)\n" +
                "    iPhone 5s (70200ED8-EEF1-4BDB-BCCF-3595B137D67D) (Shutdown)\n",
                "")));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        fakeProcessesBuilder.build());
    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        fakeProcessExecutor,
        SIMCTL_PATH,
        IOS_SIMULATOR_PATH);

    assertThat(
        appleSimulatorController.canStartSimulator("45BD7164-686C-474F-8C68-3730432BC5F2"),
        is(true));
  }

  @Test
  public void cannotStartSimulatorWhenSimulatorWithDifferentUdidExists()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            SIMCTL_LIST_PARAMS,
            new FakeProcess(
                0,
                "    iPhone 5 (45BD7164-686C-474F-8C68-3730432BC5F2) (Booted)\n" +
                "    iPhone 5s (70200ED8-EEF1-4BDB-BCCF-3595B137D67D) (Shutdown)\n",
                "")));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        fakeProcessesBuilder.build());
    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        fakeProcessExecutor,
        SIMCTL_PATH,
        IOS_SIMULATOR_PATH);

    assertThat(
        appleSimulatorController.canStartSimulator("70200ED8-EEF1-4BDB-BCCF-3595B137D67D"),
        is(false));
  }

  @Test
  public void startingSimulatorWorksWhenSimulatorNotRunning()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(new SimpleImmutableEntry<>(SIMCTL_LIST_PARAMS, new FakeProcess(0)));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
                .setCommand(
                    ImmutableList.of(
                        "open",
                        "-a",
                        IOS_SIMULATOR_PATH.toString(),
                        "--args",
                        "-CurrentDeviceUDID",
                        "70200ED8-EEF1-4BDB-BCCF-3595B137D67D"))
                .build(),
            new FakeProcess(0)));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            SIMCTL_LIST_PARAMS,
            new FakeProcess(
                0,
                "    iPhone 5 (45BD7164-686C-474F-8C68-3730432BC5F2) (Shutdown)\n" +
                "    iPhone 5s (70200ED8-EEF1-4BDB-BCCF-3595B137D67D) (Booted)\n",
                "")));

    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        fakeProcessesBuilder.build());
    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        fakeProcessExecutor,
        SIMCTL_PATH,
        IOS_SIMULATOR_PATH);

    Optional<Long> result = appleSimulatorController.startSimulator(
        "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
        1000);

    assertThat(result, is(Optional.of(0L)));
  }

  @Test
  public void startingSimulatorWorksWhenSimulatorAlreadyBooted()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            SIMCTL_LIST_PARAMS,
            new FakeProcess(
                0,
                "    iPhone 5 (45BD7164-686C-474F-8C68-3730432BC5F2) (Shutdown)\n" +
                "    iPhone 5s (70200ED8-EEF1-4BDB-BCCF-3595B137D67D) (Booted)\n",
                "")));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
                .setCommand(
                    ImmutableList.of(
                        "open",
                        "-a",
                        IOS_SIMULATOR_PATH.toString(),
                        "--args",
                        "-CurrentDeviceUDID",
                        "70200ED8-EEF1-4BDB-BCCF-3595B137D67D"))
                .build(),
            new FakeProcess(0)));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            SIMCTL_LIST_PARAMS,
            new FakeProcess(
                0,
                "    iPhone 5 (45BD7164-686C-474F-8C68-3730432BC5F2) (Shutdown)\n" +
                "    iPhone 5s (70200ED8-EEF1-4BDB-BCCF-3595B137D67D) (Booted)\n",
                "")));

    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        fakeProcessesBuilder.build());
    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        fakeProcessExecutor,
        SIMCTL_PATH,
        IOS_SIMULATOR_PATH);

    Optional<Long> result = appleSimulatorController.startSimulator(
        "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
        1000);

    assertThat(result, is(Optional.of(0L)));
  }

  @Test
  public void installingBundleInSimulatorWorks() throws IOException, InterruptedException {
    FakeProcess fakeSimctlInstallProcess = new FakeProcess(0);
    ProcessExecutorParams fakeSimctlInstallParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "path/to/simctl",
                    "install",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
                    "Path/To/MyNeatApp.app"))
            .build();
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        ImmutableMap.of(fakeSimctlInstallParams, fakeSimctlInstallProcess));
    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        fakeProcessExecutor,
        SIMCTL_PATH,
        IOS_SIMULATOR_PATH);
    boolean installed = appleSimulatorController.installBundleInSimulator(
        "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
        Paths.get("Path/To/MyNeatApp.app"));
    assertThat(installed, is(true));
  }

  @Test
  public void launchingInstalledBundleInSimulatorWorks() throws IOException, InterruptedException {
    FakeProcess fakeSimctlLaunchProcess = new FakeProcess(0, "com.facebook.MyNeatApp: 42", "");
    ProcessExecutorParams fakeSimctlLaunchParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "path/to/simctl",
                    "launch",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
                    "com.facebook.MyNeatApp"))
            .build();
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        ImmutableMap.of(fakeSimctlLaunchParams, fakeSimctlLaunchProcess));
    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        fakeProcessExecutor,
        SIMCTL_PATH,
        IOS_SIMULATOR_PATH);
    Optional<Long> launchedPID = appleSimulatorController.launchInstalledBundleInSimulator(
        "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
        "com.facebook.MyNeatApp",
        AppleSimulatorController.LaunchBehavior.DO_NOT_WAIT_FOR_DEBUGGER,
        ImmutableList.<String>of());
    assertThat(launchedPID, is(equalTo(Optional.of(42L))));
  }

  @Test
  public void launchingInstalledBundleWaitingForDebuggerWorks()
        throws IOException, InterruptedException {
    FakeProcess fakeSimctlLaunchProcess = new FakeProcess(0, "com.facebook.MyNeatApp: 42", "");
    ProcessExecutorParams fakeSimctlLaunchParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "path/to/simctl",
                    "launch",
                    "-w",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
                    "com.facebook.MyNeatApp"))
            .build();
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        ImmutableMap.of(fakeSimctlLaunchParams, fakeSimctlLaunchProcess));
    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        fakeProcessExecutor,
        SIMCTL_PATH,
        IOS_SIMULATOR_PATH);
    Optional<Long> launchedPID = appleSimulatorController.launchInstalledBundleInSimulator(
          "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
          "com.facebook.MyNeatApp",
          AppleSimulatorController.LaunchBehavior.WAIT_FOR_DEBUGGER,
        ImmutableList.<String>of());

    assertThat(launchedPID, is(equalTo(Optional.of(42L))));
  }

  @Test
  public void launchingInstalledBundleWithArgsPassesArgsThroughToSimCtl()
      throws IOException, InterruptedException {
    FakeProcess fakeSimctlLaunchProcess = new FakeProcess(0, "com.facebook.MyNeatApp: 42", "");
    ProcessExecutorParams fakeSimctlLaunchParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "path/to/simctl",
                    "launch",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
                    "com.facebook.MyNeatApp",
                    "arg1",
                    "arg2"))
            .build();
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        ImmutableMap.of(fakeSimctlLaunchParams, fakeSimctlLaunchProcess));
    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        fakeProcessExecutor,
        SIMCTL_PATH,
        IOS_SIMULATOR_PATH);
    Optional<Long> launchedPID = appleSimulatorController.launchInstalledBundleInSimulator(
        "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
        "com.facebook.MyNeatApp",
        AppleSimulatorController.LaunchBehavior.DO_NOT_WAIT_FOR_DEBUGGER,
        ImmutableList.<String>of("arg1", "arg2"));
    assertThat(launchedPID, is(equalTo(Optional.of(42L))));
  }
}
