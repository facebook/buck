/*
 * Copyright 2019-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

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
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link AppleDeviceController}. */
public class AppleDeviceControllerTest {

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void getSimulatorsTest() throws IOException {
    ImmutableSet<ImmutableAppleDevice> simulators;
    try (OutputStream stdin = new ByteArrayOutputStream();
        InputStream stdout = getClass().getResourceAsStream("testdata/idb-list.txt");
        InputStream stderr = new ByteArrayInputStream(new byte[0])) {
      FakeProcess fakeIdbList = new FakeProcess(0, stdin, stdout, stderr);
      ProcessExecutorParams processExecutorParams =
          ProcessExecutorParams.builder()
              .setCommand(ImmutableList.of("/pathTo/idb", "list-targets", "--json"))
              .build();
      FakeProcessExecutor fakeProcessExecutor =
          new FakeProcessExecutor(ImmutableMap.of(processExecutorParams, fakeIdbList));
      AppleDeviceController deviceController =
          new AppleDeviceController(fakeProcessExecutor, Paths.get("/pathTo/idb"));
      simulators = deviceController.getSimulators();
    }

    ImmutableSet<ImmutableAppleDevice> expected =
        ImmutableSet.of(
            (new ImmutableAppleDevice(
                "Apple Watch Series 2 - 38mm",
                "",
                "Shutdown",
                "simulator",
                "watchOS 5.2",
                "i386",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "Apple Watch Series 2 - 42mm",
                "",
                "Shutdown",
                "simulator",
                "watchOS 5.2",
                "i386",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "Apple Watch Series 3 - 38mm",
                "",
                "Shutdown",
                "simulator",
                "watchOS 5.2",
                "i386",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "Apple Watch Series 3 - 42mm",
                "",
                "Shutdown",
                "simulator",
                "watchOS 5.2",
                "i386",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "Apple Watch Series 4 - 40mm",
                "",
                "Shutdown",
                "simulator",
                "watchOS 5.2",
                "i386",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "Apple Watch Series 4 - 44mm",
                "",
                "Shutdown",
                "simulator",
                "watchOS 5.2",
                "i386",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPad Air (3rd generation)",
                "",
                "Booted",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPhone 5s", "", "Shutdown", "simulator", "iOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "iPhone 6", "", "Shutdown", "simulator", "iOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "iPhone 6 Plus",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPhone 6s", "", "Shutdown", "simulator", "iOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "iPhone 6s Plus",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPhone 7", "", "Shutdown", "simulator", "iOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "iPhone 7 Plus",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPhone 8", "", "Shutdown", "simulator", "iOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "iPhone 8 Plus",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPhone SE", "", "Shutdown", "simulator", "iOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "iPhone X", "", "Shutdown", "simulator", "iOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "iPhone Xs", "", "Shutdown", "simulator", "iOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "iPhone Xs Max",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPhone X\u0280",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPad (5th generation)",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPad (6th generation)",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPad Air", "", "Shutdown", "simulator", "iOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "iPad Air 2", "", "Shutdown", "simulator", "iOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "iPad Pro (10.5-inch)",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPad Pro (11-inch)",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPad Pro (12.9-inch)",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPad Pro (12.9-inch) (2nd generation)",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPad Pro (12.9-inch) (3rd generation)",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "iPad Pro (9.7-inch)",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "Apple TV", "", "Shutdown", "simulator", "tvOS 12.2", "x86_64", "", "0", "false")),
            (new ImmutableAppleDevice(
                "Apple TV 4K",
                "",
                "Shutdown",
                "simulator",
                "tvOS 12.2",
                "x86_64",
                "",
                "0",
                "false")),
            (new ImmutableAppleDevice(
                "Apple TV 4K (at 1080p)",
                "",
                "Shutdown",
                "simulator",
                "tvOS 12.2",
                "x86_64",
                "",
                "0",
                "false")));

    assertEquals(simulators, expected);
  }

  @Test
  public void getBootedSimulatorDeviceUdidsTest() throws IOException, InterruptedException {
    ImmutableSet<String> devices;
    try (OutputStream stdin = new ByteArrayOutputStream();
        InputStream stdout = getClass().getResourceAsStream("testdata/idb-list.txt");
        InputStream stderr = new ByteArrayInputStream(new byte[0])) {
      FakeProcess fakeIdbList = new FakeProcess(0, stdin, stdout, stderr);
      ProcessExecutorParams processExecutorParams =
          ProcessExecutorParams.builder()
              .setCommand(ImmutableList.of("/pathTo/idb", "list-targets", "--json"))
              .build();
      FakeProcessExecutor fakeProcessExecutor =
          new FakeProcessExecutor(ImmutableMap.of(processExecutorParams, fakeIdbList));

      AppleDeviceController deviceController =
          new AppleDeviceController(fakeProcessExecutor, Paths.get("/pathTo/idb"));
      devices = deviceController.getBootedSimulatorsUdids();
    }

    ImmutableSet<String> expected = ImmutableSet.of("");

    assertEquals(devices, expected);
  }
}
