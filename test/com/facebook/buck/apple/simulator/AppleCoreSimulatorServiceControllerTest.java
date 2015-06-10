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
import com.facebook.buck.util.FakeUserIdFetcher;
import com.facebook.buck.util.ProcessExecutorParams;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.io.IOException;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Map;

import java.util.AbstractMap.SimpleImmutableEntry;

import org.junit.Test;

/**
 * Unit tests for {@link AppleCoreSimulatorServiceController}.
 */
public class AppleCoreSimulatorServiceControllerTest {

  private static final ProcessExecutorParams LAUNCHCTL_LIST_PARAMS =
      ProcessExecutorParams.builder()
          .setCommand(ImmutableList.of("launchctl", "list"))
          .build();

  @Test
  public void coreSimulatorServicePathFetchedFromLaunchctlPrint()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            LAUNCHCTL_LIST_PARAMS,
            new FakeProcess(
                0,
                "87823\t0\tcom.apple.CoreSimulator.CoreSimulatorService.117.15.1.lkhDXxRPp5yy\n",
                "")));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
                .setCommand(
                    ImmutableList.of(
                        "launchctl",
                        "print",
                        "user/42/com.apple.CoreSimulator.CoreSimulatorService.117.15.1.lkhDXxRPp5yy"
                    ))
                .build(),
            new FakeProcess(
                0,
                "com.apple.CoreSimulator.CoreSimulatorService.117.15.1.lkhDXxRPp5yy = {\n" +
                "    path = xcode-dir/Developer/Library/PrivateFrameworks/CoreSimulator.framework" +
                "/Versions/A/XPCServices/com.apple.CoreSimulator.CoreSimulatorService.xpc\n" +
                "}\n",
                "")));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        fakeProcessesBuilder.build());
    AppleCoreSimulatorServiceController appleCoreSimulatorServiceController =
        new AppleCoreSimulatorServiceController(fakeProcessExecutor);
    Optional<Path> coreSimulatorServicePath =
        appleCoreSimulatorServiceController.getCoreSimulatorServicePath(
            new FakeUserIdFetcher(42));
    Optional<Path> expected =
        Optional.of(
            Paths.get("xcode-dir/Developer/Library/PrivateFrameworks/CoreSimulator.framework/" +
                      "Versions/A/XPCServices/com.apple.CoreSimulator.CoreSimulatorService.xpc"));
    assertThat(coreSimulatorServicePath, is(equalTo(expected)));
  }

  @Test
  public void coreSimulatorServicesKilledSuccessfully()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            LAUNCHCTL_LIST_PARAMS,
            new FakeProcess(
                0,
                "87823\t0\tcom.apple.CoreSimulator.CoreSimulatorService.117.15.1.lkhDXxRPp5yy\n" +
                "74617\t0\tcom.apple.CoreSimulator.SimDevice.CC1B0BAD-BAE6-4A53-92CF-F79850654057" +
                ".launchd_sim\n" +
                "74614\t0\tcom.apple.iphonesimulator.6564\n",
                "")));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "launchctl",
                    "remove",
                    "com.apple.CoreSimulator.CoreSimulatorService.117.15.1.lkhDXxRPp5yy"))
            .build(),
            new FakeProcess(0)));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "launchctl",
                    "remove",
                    "com.apple.CoreSimulator.SimDevice.CC1B0BAD-BAE6-4A53-92CF-F79850654057." +
                    "launchd_sim"))
            .build(),
            new FakeProcess(0)));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "launchctl",
                    "remove",
                    "com.apple.iphonesimulator.6564"))
            .build(),
            new FakeProcess(0)));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        fakeProcessesBuilder.build());
    AppleCoreSimulatorServiceController appleCoreSimulatorServiceController =
        new AppleCoreSimulatorServiceController(fakeProcessExecutor);
    assertThat(appleCoreSimulatorServiceController.killSimulatorProcesses(), is(true));
  }

  @Test
  public void coreSimulatorServicesKillSucceedsEvenIfNoSuchProcess()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            LAUNCHCTL_LIST_PARAMS,
            new FakeProcess(
                0,
                "87823\t0\tcom.apple.CoreSimulator.CoreSimulatorService.117.15.1.lkhDXxRPp5yy\n" +
                "74617\t0\tcom.apple.CoreSimulator.SimDevice.CC1B0BAD-BAE6-4A53-92CF-F79850654057" +
                ".launchd_sim\n" +
                "74614\t0\tcom.apple.iphonesimulator.6564\n",
                "")));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "launchctl",
                    "remove",
                    "com.apple.CoreSimulator.CoreSimulatorService.117.15.1.lkhDXxRPp5yy"))
            .build(),
            new FakeProcess(0)));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "launchctl",
                    "remove",
                    "com.apple.CoreSimulator.SimDevice.CC1B0BAD-BAE6-4A53-92CF-F79850654057." +
                    "launchd_sim"))
            .build(),
            new FakeProcess(3)));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "launchctl",
                    "remove",
                    "com.apple.iphonesimulator.6564"))
            .build(),
            new FakeProcess(0)));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        fakeProcessesBuilder.build());
    AppleCoreSimulatorServiceController appleCoreSimulatorServiceController =
        new AppleCoreSimulatorServiceController(fakeProcessExecutor);
    assertThat(appleCoreSimulatorServiceController.killSimulatorProcesses(), is(true));
  }

  @Test
  public void coreSimulatorServicesKillFailsIfUnrecognizedError()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            LAUNCHCTL_LIST_PARAMS,
            new FakeProcess(
                0,
                "87823\t0\tcom.apple.CoreSimulator.CoreSimulatorService.117.15.1.lkhDXxRPp5yy\n",
                "")));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    "launchctl",
                    "remove",
                    "com.apple.CoreSimulator.CoreSimulatorService.117.15.1.lkhDXxRPp5yy"))
            .build(),
            new FakeProcess(42)));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(
        fakeProcessesBuilder.build());
    AppleCoreSimulatorServiceController appleCoreSimulatorServiceController =
        new AppleCoreSimulatorServiceController(fakeProcessExecutor);
    assertThat(appleCoreSimulatorServiceController.killSimulatorProcesses(), is(false));
  }
}
