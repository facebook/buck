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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link AppleDeviceController}. */
public class AppleDeviceControllerTest {
  private static final Path IDB_PATH = Paths.get("/path/to/idb");
  private static final ProcessExecutorParams IDB_LIST_TARGETS_PARAMS =
      ProcessExecutorParams.builder()
          .setCommand(ImmutableList.of(IDB_PATH.toString(), "list-targets", "--json"))
          .build();

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void canStartSimulatorWhenNoSimulatorBooted() throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            IDB_LIST_TARGETS_PARAMS,
            new FakeProcess(
                0,
                "       {\"name\": \"iPhone 5s\", \"udid\": \"C93784E0-6044-452B-BA9F-5A10AC97F19C\", \"state\": \"Shutdown\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n"
                    + "       {\"name\": \"iPhone 6\", \"udid\": \"06B09158-FA9C-4E44-AC2B-7A8FEB1D3DFA\", \"state\": \"Shutdown\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n",
                "")));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(fakeProcessesBuilder.build());
    AppleDeviceController appleDeviceController =
        new AppleDeviceController(fakeProcessExecutor, IDB_PATH);

    assertThat(
        appleDeviceController.isSimulatorAvailable("C93784E0-6044-452B-BA9F-5A10AC97F19C"),
        is(true));
  }

  @Test
  public void canStartSimulatorWhenSimulatorAlreadyBooted()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            IDB_LIST_TARGETS_PARAMS,
            new FakeProcess(
                0,
                "       {\"name\": \"iPhone 5s\", \"udid\": \"C93784E0-6044-452B-BA9F-5A10AC97F19C\", \"state\": \"Booted\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n"
                    + "       {\"name\": \"iPhone 6\", \"udid\": \"06B09158-FA9C-4E44-AC2B-7A8FEB1D3DFA\", \"state\": \"Shutdown\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n",
                "")));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(fakeProcessesBuilder.build());
    AppleDeviceController appleDeviceController =
        new AppleDeviceController(fakeProcessExecutor, IDB_PATH);

    assertThat(
        appleDeviceController.isSimulatorAvailable("C93784E0-6044-452B-BA9F-5A10AC97F19C"),
        is(true));
  }

  @Test
  public void canStartSimulatorWhenSimulatorWithDifferentUdidExists()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            IDB_LIST_TARGETS_PARAMS,
            new FakeProcess(
                0,
                "       {\"name\": \"iPhone 5s\", \"udid\": \"C93784E0-6044-452B-BA9F-5A10AC97F19C\", \"state\": \"Booted\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n"
                    + "       {\"name\": \"iPhone 6\", \"udid\": \"06B09158-FA9C-4E44-AC2B-7A8FEB1D3DFA\", \"state\": \"Shutdown\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n",
                "")));
    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(fakeProcessesBuilder.build());
    AppleDeviceController appleDeviceController =
        new AppleDeviceController(fakeProcessExecutor, IDB_PATH);

    assertThat(
        appleDeviceController.isSimulatorAvailable("06B09158-FA9C-4E44-AC2B-7A8FEB1D3DFA"),
        is(true));
  }

  @Test
  public void startingSimulatorWorksWhenSimulatorNotRunning()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            IDB_LIST_TARGETS_PARAMS,
            new FakeProcess(
                0,
                "       {\"name\": \"iPhone X\", \"udid\": \"D13E6888-3642-4B8F-9403-812D389F16DF\", \"state\": \"Shutdown\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n"
                    + "       {\"name\": \"iPhone Xs\", \"udid\": \"AE576BA3-983C-47D6-B043-EEAE5F62F38D\", \"state\": \"Booted\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n",
                "")));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
                .setCommand(
                    ImmutableList.of(
                        IDB_PATH.toString(),
                        "boot",
                        "--udid",
                        "AE576BA3-983C-47D6-B043-EEAE5F62F38D"))
                .build(),
            new FakeProcess(0)));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            IDB_LIST_TARGETS_PARAMS,
            new FakeProcess(
                0,
                "       {\"name\": \"iPhone X\", \"udid\": \"D13E6888-3642-4B8F-9403-812D389F16DF\", \"state\": \"Shutdown\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n"
                    + "       {\"name\": \"iPhone Xs\", \"udid\": \"AE576BA3-983C-47D6-B043-EEAE5F62F38D\", \"state\": \"Booted\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n",
                "")));

    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(fakeProcessesBuilder.build());
    AppleDeviceController appleDeviceController =
        new AppleDeviceController(fakeProcessExecutor, IDB_PATH);

    boolean result = appleDeviceController.bootSimulator("AE576BA3-983C-47D6-B043-EEAE5F62F38D");

    assertThat(result, is(true));
  }

  @Test
  public void startingSimulatorWorksWhenSimulatorAlreadyBooted()
      throws IOException, InterruptedException {
    ImmutableList.Builder<Map.Entry<ProcessExecutorParams, FakeProcess>> fakeProcessesBuilder =
        ImmutableList.builder();
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            IDB_LIST_TARGETS_PARAMS,
            new FakeProcess(
                0,
                "       {\"name\": \"iPhone X\", \"udid\": \"D13E6888-3642-4B8F-9403-812D389F16DF\", \"state\": \"Shutdown\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n"
                    + "       {\"name\": \"iPhone Xs\", \"udid\": \"AE576BA3-983C-47D6-B043-EEAE5F62F38D\", \"state\": \"Booted\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n",
                "")));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            ProcessExecutorParams.builder()
                .setCommand(
                    ImmutableList.of(
                        IDB_PATH.toString(),
                        "boot",
                        "--udid",
                        "AE576BA3-983C-47D6-B043-EEAE5F62F38D"))
                .build(),
            new FakeProcess(0)));
    fakeProcessesBuilder.add(
        new SimpleImmutableEntry<>(
            IDB_LIST_TARGETS_PARAMS,
            new FakeProcess(
                0,
                "       {\"name\": \"iPhone X\", \"udid\": \"D13E6888-3642-4B8F-9403-812D389F16DF\", \"state\": \"Shutdown\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n"
                    + "       {\"name\": \"iPhone Xs\", \"udid\": \"AE576BA3-983C-47D6-B043-EEAE5F62F38D\", \"state\": \"Booted\", \"type\": \"simulator\", \"os_version\": \"iOS 12.2\", \"architecture\": \"x86_64\", \"host\": \"\", \"port\": 0, \"is_local\": false}\n",
                "")));

    FakeProcessExecutor fakeProcessExecutor = new FakeProcessExecutor(fakeProcessesBuilder.build());
    AppleDeviceController appleDeviceController =
        new AppleDeviceController(fakeProcessExecutor, IDB_PATH);

    boolean result = appleDeviceController.bootSimulator("AE576BA3-983C-47D6-B043-EEAE5F62F38D");

    assertThat(result, is(true));
  }

  @Test
  public void installingBundleInSimulatorWorks() throws IOException, InterruptedException {
    FakeProcess fakeInstallProcess =
        new FakeProcess(0, "Installed: com.MyNeatApp 9CBB9A6E-A511-C503-F000-7EB616BE0CBC", "");
    ProcessExecutorParams fakeInstallParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    IDB_PATH.toString(),
                    "install",
                    "--udid",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D",
                    "Path/To/MyNeatApp.app"))
            .build();
    FakeProcessExecutor fakeProcessExecutor =
        new FakeProcessExecutor(ImmutableMap.of(fakeInstallParams, fakeInstallProcess));
    AppleDeviceController deviceController =
        new AppleDeviceController(fakeProcessExecutor, IDB_PATH);
    Optional<String> installedResult =
        deviceController.installBundle(
            "70200ED8-EEF1-4BDB-BCCF-3595B137D67D", Paths.get("Path/To/MyNeatApp.app"));
    assertThat(installedResult.get(), is("com.MyNeatApp"));
  }

  @Test
  public void bringSimulatorToFrontTest() throws IOException, InterruptedException {
    FakeProcess fakeProcess = new FakeProcess(0);
    ProcessExecutorParams fakeProcessParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    IDB_PATH.toString(), "focus", "--udid", "70200ED8-EEF1-4BDB-BCCF-3595B137D67D"))
            .build();
    FakeProcessExecutor fakeProcessExecutor =
        new FakeProcessExecutor(ImmutableMap.of(fakeProcessParams, fakeProcess));

    AppleDeviceController deviceController =
        new AppleDeviceController(fakeProcessExecutor, IDB_PATH);
    boolean inFront =
        deviceController.bringSimulatorToFront("70200ED8-EEF1-4BDB-BCCF-3595B137D67D");
    assertThat(inFront, is(true));
  }

  @Test
  public void launchingInstalledBundleInSimulatorWorks() throws IOException, InterruptedException {
    FakeProcess fakeLaunchProcess = new FakeProcess(0, "com.facebook.MyNeatApp: 42", "");
    ProcessExecutorParams fakeLaunchParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    IDB_PATH.toString(),
                    "launch",
                    "com.facebook.MyNeatApp",
                    "--udid",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D"))
            .build();
    FakeProcessExecutor fakeProcessExecutor =
        new FakeProcessExecutor(ImmutableMap.of(fakeLaunchParams, fakeLaunchProcess));
    AppleDeviceController appleDeviceController =
        new AppleDeviceController(fakeProcessExecutor, IDB_PATH);
    boolean launchStatus =
        appleDeviceController.launchInstalledBundle(
            "70200ED8-EEF1-4BDB-BCCF-3595B137D67D", "com.facebook.MyNeatApp");
    assertThat(launchStatus, is(true));
  }

  @Test
  public void launchingInstalledBundleWaitingForDebuggerWorks()
      throws IOException, InterruptedException {
    FakeProcess fakeLaunchProcess =
        new FakeProcess(0, "process connect connect://localhost:10881", "");
    ProcessExecutorParams fakeLaunchParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    IDB_PATH.toString(),
                    "debugserver",
                    "start",
                    "com.facebook.MyNeatApp",
                    "--udid",
                    "70200ED8-EEF1-4BDB-BCCF-3595B137D67D"))
            .build();
    FakeProcessExecutor fakeProcessExecutor =
        new FakeProcessExecutor(ImmutableMap.of(fakeLaunchParams, fakeLaunchProcess));
    AppleDeviceController appleDeviceController =
        new AppleDeviceController(fakeProcessExecutor, IDB_PATH);
    Optional<String> debugServer =
        appleDeviceController.startDebugServer(
            "70200ED8-EEF1-4BDB-BCCF-3595B137D67D", "com.facebook.MyNeatApp");

    assertThat(debugServer, is(Optional.of("process connect connect://localhost:10881\n")));
  }

  @Test
  public void getSimulatorsTest() throws IOException {
    ImmutableSet<AppleDevice> simulators;
    try (OutputStream stdin = new ByteArrayOutputStream();
        InputStream stdout = getClass().getResourceAsStream("testdata/idb-list.txt");
        InputStream stderr = new ByteArrayInputStream(new byte[0])) {
      FakeProcess fakeIdbList = new FakeProcess(0, stdin, stdout, stderr);
      ProcessExecutorParams processExecutorParams =
          ProcessExecutorParams.builder()
              .setCommand(ImmutableList.of(IDB_PATH.toString(), "list-targets", "--json"))
              .build();
      FakeProcessExecutor fakeProcessExecutor =
          new FakeProcessExecutor(ImmutableMap.of(processExecutorParams, fakeIdbList));
      AppleDeviceController deviceController =
          new AppleDeviceController(fakeProcessExecutor, IDB_PATH);
      simulators = deviceController.getSimulators();
    }

    ImmutableSet<ImmutableAppleDevice> expected =
        ImmutableSet.of(
            (ImmutableAppleDevice.of(
                "Apple Watch Series 2 - 38mm", "", "Shutdown", "simulator", "watchOS 5.2", "i386")),
            (ImmutableAppleDevice.of(
                "Apple Watch Series 2 - 42mm", "", "Shutdown", "simulator", "watchOS 5.2", "i386")),
            (ImmutableAppleDevice.of(
                "Apple Watch Series 3 - 38mm", "", "Shutdown", "simulator", "watchOS 5.2", "i386")),
            (ImmutableAppleDevice.of(
                "Apple Watch Series 3 - 42mm", "", "Shutdown", "simulator", "watchOS 5.2", "i386")),
            (ImmutableAppleDevice.of(
                "Apple Watch Series 4 - 40mm", "", "Shutdown", "simulator", "watchOS 5.2", "i386")),
            (ImmutableAppleDevice.of(
                "Apple Watch Series 4 - 44mm", "", "Shutdown", "simulator", "watchOS 5.2", "i386")),
            (ImmutableAppleDevice.of(
                "iPad Air (3rd generation)", "", "Booted", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone 5s", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone 6", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone 6 Plus", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone 6s", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone 6s Plus", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone 7", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone 7 Plus", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone 8", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone 8 Plus", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone SE", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone X",
                "DF85F9BE-70D1-4706-B95F-58CD25986051",
                "Shutdown",
                "simulator",
                "iOS 12.4",
                "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone Xs", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone Xs Max", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPhone X\u0280", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPad (5th generation)", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPad (6th generation)", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPad Air", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPad Air 2", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPad Pro (10.5-inch)", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPad Pro (11-inch)", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPad Pro (12.9-inch)", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "iPad Pro (12.9-inch) (2nd generation)",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64")),
            (ImmutableAppleDevice.of(
                "iPad Pro (12.9-inch) (3rd generation)",
                "",
                "Shutdown",
                "simulator",
                "iOS 12.2",
                "x86_64")),
            (ImmutableAppleDevice.of(
                "iPad Pro (9.7-inch)", "", "Shutdown", "simulator", "iOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "Apple TV", "", "Shutdown", "simulator", "tvOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "Apple TV 4K", "", "Shutdown", "simulator", "tvOS 12.2", "x86_64")),
            (ImmutableAppleDevice.of(
                "Apple TV 4K (at 1080p)", "", "Shutdown", "simulator", "tvOS 12.2", "x86_64")));

    assertEquals(simulators, expected);
  }

  @Test
  public void getPhysicalDevicesTest() throws IOException {
    ImmutableSet<AppleDevice> physicalDevices;
    try (OutputStream stdin = new ByteArrayOutputStream();
        InputStream stdout = getClass().getResourceAsStream("testdata/idb-list.txt");
        InputStream stderr = new ByteArrayInputStream(new byte[0])) {
      FakeProcess fakeIdbList = new FakeProcess(0, stdin, stdout, stderr);
      ProcessExecutorParams processExecutorParams =
          ProcessExecutorParams.builder()
              .setCommand(ImmutableList.of(IDB_PATH.toString(), "list-targets", "--json"))
              .build();
      FakeProcessExecutor fakeProcessExecutor =
          new FakeProcessExecutor(ImmutableMap.of(processExecutorParams, fakeIdbList));
      AppleDeviceController deviceController =
          new AppleDeviceController(fakeProcessExecutor, IDB_PATH);
      physicalDevices = deviceController.getPhysicalDevices();
    }

    ImmutableSet<ImmutableAppleDevice> expected =
        ImmutableSet.of(
            ImmutableAppleDevice.of("iPhone", "", "Booted", "device", "iOS 12.4", "arm64"));

    assertEquals(physicalDevices, expected);
  }

  @Test
  public void getBootedSimulatorDeviceUdidsTest() throws IOException {
    ImmutableSet<String> devices;
    try (OutputStream stdin = new ByteArrayOutputStream();
        InputStream stdout = getClass().getResourceAsStream("testdata/idb-list.txt");
        InputStream stderr = new ByteArrayInputStream(new byte[0])) {
      FakeProcess fakeIdbList = new FakeProcess(0, stdin, stdout, stderr);
      ProcessExecutorParams processExecutorParams =
          ProcessExecutorParams.builder()
              .setCommand(ImmutableList.of(IDB_PATH.toString(), "list-targets", "--json"))
              .build();
      FakeProcessExecutor fakeProcessExecutor =
          new FakeProcessExecutor(ImmutableMap.of(processExecutorParams, fakeIdbList));

      AppleDeviceController deviceController =
          new AppleDeviceController(fakeProcessExecutor, IDB_PATH);
      devices = deviceController.getBootedSimulatorsUdids();
    }

    ImmutableSet<String> expected = ImmutableSet.of("");

    assertEquals(devices, expected);
  }

  @Test
  public void getUdidFromDeviceName() throws IOException {
    Optional<String> udid;
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
      udid = deviceController.getUdidFromDeviceName("iPhone X");
    }

    Optional<String> expected = Optional.of("DF85F9BE-70D1-4706-B95F-58CD25986051");
    assertEquals(udid, expected);
  }
}
