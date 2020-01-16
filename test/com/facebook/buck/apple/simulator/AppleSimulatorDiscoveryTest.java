/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple.simulator;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link AppleSimulatorDiscovery}. */
public class AppleSimulatorDiscoveryTest {
  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void appleSimulatorsDiscoveredFromSimctlList() throws IOException, InterruptedException {
    ImmutableSet<AppleSimulator> simulators;
    try (OutputStream stdin = new ByteArrayOutputStream();
        InputStream stdout = getClass().getResourceAsStream("testdata/simctl-list.txt");
        InputStream stderr = new ByteArrayInputStream(new byte[0])) {
      FakeProcess fakeXcrunSimctlList = new FakeProcess(0, stdin, stdout, stderr);
      ProcessExecutorParams processExecutorParams =
          ProcessExecutorParams.builder()
              .setCommand(ImmutableList.of("path/to/simctl", "list"))
              .build();
      FakeProcessExecutor fakeProcessExecutor =
          new FakeProcessExecutor(ImmutableMap.of(processExecutorParams, fakeXcrunSimctlList));
      simulators =
          AppleSimulatorDiscovery.discoverAppleSimulators(
              fakeProcessExecutor, Paths.get("path/to/simctl"));
    }

    ImmutableSet<AppleSimulator> expected =
        ImmutableSet.<AppleSimulator>builder()
            .add(
                ImmutableAppleSimulator.of(
                    "iPhone 4s",
                    "F7C1CC9A-945E-4258-BA84-DEEBE683798B",
                    AppleSimulatorState.SHUTDOWN))
            .add(
                ImmutableAppleSimulator.of(
                    "iPhone 5",
                    "45BD7164-686C-474F-8C68-3730432BC5F2",
                    AppleSimulatorState.SHUTDOWN))
            .add(
                ImmutableAppleSimulator.of(
                    "iPhone 5s",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
                    AppleSimulatorState.BOOTED))
            .add(
                ImmutableAppleSimulator.of(
                    "iPhone 6 Plus",
                    "92340ACF-2C44-455F-BACD-573B133FB20E",
                    AppleSimulatorState.SHUTDOWN))
            .add(
                ImmutableAppleSimulator.of(
                    "iPhone 6",
                    "A75FF972-FE12-4656-A8CC-99572879D4A3",
                    AppleSimulatorState.SHUTDOWN))
            .add(
                ImmutableAppleSimulator.of(
                    "iPhone SE Trailing Whitespace",
                    "CBB0391A-118C-39BA-CA29-11405CA10BC1",
                    AppleSimulatorState.SHUTDOWN))
            .add(
                ImmutableAppleSimulator.of(
                    "iPhone ANSI Color",
                    "7313DF34-884C-49F7-8261-B26377F9FC23",
                    AppleSimulatorState.SHUTDOWN))
            .add(
                ImmutableAppleSimulator.of(
                    "iPad 2",
                    "CC1B0BAD-BAE6-4A53-92CF-F79850654057",
                    AppleSimulatorState.SHUTTING_DOWN))
            .add(
                ImmutableAppleSimulator.of(
                    "iPad Retina",
                    "137AAA25-54A1-42E8-8202-84DEADD668E1",
                    AppleSimulatorState.SHUTDOWN))
            .add(
                ImmutableAppleSimulator.of(
                    "iPad Air",
                    "554B2E0F-63F3-4400-8319-5C5062CF4C95",
                    AppleSimulatorState.SHUTDOWN))
            .add(
                ImmutableAppleSimulator.of(
                    "Resizable iPhone",
                    "58E3748F-F7E6-4A45-B52C-A136B59F7A42",
                    AppleSimulatorState.CREATING))
            .add(
                ImmutableAppleSimulator.of(
                    "Resizable iPad",
                    "56FE1CBC-61FF-443D-8E23-19D05864C6DB",
                    AppleSimulatorState.SHUTDOWN))
            .build();

    assertThat(simulators, is(equalTo(expected)));
  }

  @Test
  public void appleSimulatorProfileDiscoveredFromPlist() throws Exception {
    AppleSimulator simulator =
        ImmutableAppleSimulator.of(
            "iPhone 5s", "70200ED8-EEF1-4BDB-BCCF-3595B137D67D", AppleSimulatorState.BOOTED);
    Optional<AppleSimulatorProfile> simulatorProfile =
        AppleSimulatorDiscovery.discoverAppleSimulatorProfile(
            simulator, TestDataHelper.getTestDataDirectory(this));

    Optional<AppleSimulatorProfile> expected =
        Optional.of(
            ImmutableAppleSimulatorProfile.builder()
                .addSupportedProductFamilyIDs(1)
                .addSupportedArchitectures("i386", "x86_64")
                .build());

    assertThat(simulatorProfile, is(equalTo(expected)));
  }

  @Test
  public void appleSimulatorsDiscoveredFromRetrySimctlList()
      throws IOException, InterruptedException {
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("path/to/simctl", "list"))
            .build();

    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(42, "", "")));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(42, "", "")));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(processExecutorParams, new FakeProcess(0, "", "")));

    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(fakeProcessesBuilder.build());

    AppleSimulatorDiscovery.discoverAppleSimulators(
        fakeProcessExecutor, Paths.get("path/to/simctl"));
  }
}
