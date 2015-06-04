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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.nio.file.Paths;

import org.junit.Test;

/**
 * Unit tests for {@link AppleSimulatorRunning}.
 */
public class AppleSimulatorRunningTest {
  @Test
  public void startingSimulatorWorks() throws IOException, InterruptedException {
    FakeProcess fakeSimulatorProcess = new FakeProcess(0);
    ProcessExecutorParams fakeSimulatorParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "open",
                    "-a",
                    "xcode-dir/Applications/iOS Simulator.app",
                    "--args",
                    "-CurrentDeviceUDID",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D"))
            .build();
    boolean simulatorStarted;
    try (OutputStream stdin = new ByteArrayOutputStream();
         InputStream stdout = getClass().getResourceAsStream("testdata/simctl-list.txt");
         InputStream stderr = new ByteArrayInputStream(new byte[0])) {
      FakeProcess fakeSimctlListProcess = new FakeProcess(0, stdin, stdout, stderr);
      ProcessExecutorParams fakeSimctlListParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("xcrun", "simctl", "list"))
            .build();
      FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
          ImmutableMap.of(
              fakeSimulatorParams, fakeSimulatorProcess,
              fakeSimctlListParams, fakeSimctlListProcess));
      simulatorStarted = AppleSimulatorRunning.startSimulator(
          fakeProcessExecutor,
          Paths.get("xcode-dir"),
          "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
          1000);
    }

    assertThat(simulatorStarted, is(true));
  }

  @Test
  public void installingBundleInSimulatorWorks() throws IOException, InterruptedException {
    FakeProcess fakeSimctlInstallProcess = new FakeProcess(0);
    ProcessExecutorParams fakeSimctlInstallParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "xcrun",
                    "simctl",
                    "install",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
                    "Path/To/MyNeatApp.app"))
            .build();
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        ImmutableMap.of(fakeSimctlInstallParams, fakeSimctlInstallProcess));
    boolean installed = AppleSimulatorRunning.installBundleInSimulator(
          fakeProcessExecutor,
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
                    "xcrun",
                    "simctl",
                    "launch",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
                    "com.facebook.MyNeatApp"))
            .build();
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        ImmutableMap.of(fakeSimctlLaunchParams, fakeSimctlLaunchProcess));
    Optional<Long> launchedPID = AppleSimulatorRunning.launchInstalledBundleInSimulator(
          fakeProcessExecutor,
          "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
          "com.facebook.MyNeatApp",
          false);
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
                    "xcrun",
                    "simctl",
                    "launch",
                    "-w",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
                    "com.facebook.MyNeatApp"))
            .build();
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        ImmutableMap.of(fakeSimctlLaunchParams, fakeSimctlLaunchProcess));
    Optional<Long> launchedPID = AppleSimulatorRunning.launchInstalledBundleInSimulator(
          fakeProcessExecutor,
          "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
          "com.facebook.MyNeatApp",
          true);
    assertThat(launchedPID, is(equalTo(Optional.of(42L))));
  }
}
