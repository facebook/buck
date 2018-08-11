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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.IDevice;
import com.facebook.buck.android.exopackage.AndroidDevice;
import com.facebook.buck.android.exopackage.RealAndroidDevice;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

public class AdbHelperTest {

  private TestConsole testConsole;
  private ExecutionContext testContext;
  private AdbHelper basicAdbHelper;

  @Before
  public void setUp() throws CmdLineException {
    testContext = TestExecutionContext.newInstance();
    testConsole = (TestConsole) testContext.getConsole();
    basicAdbHelper = createAdbHelper(createAdbOptions(), new TargetDeviceOptions());
  }

  private TestDevice createRealDevice(String serial, IDevice.DeviceState state) {
    TestDevice device = TestDevice.createRealDevice(serial);
    device.setState(state);
    return device;
  }

  private TestDevice createEmulator(String serial, IDevice.DeviceState state) {
    TestDevice device = TestDevice.createEmulator(serial);
    device.setState(state);
    return device;
  }

  private AdbHelper createAdbHelper(AdbOptions adbOptions, TargetDeviceOptions targetDeviceOptions)
      throws CmdLineException {
    return createAdbHelper(testContext, adbOptions, targetDeviceOptions);
  }

  private AdbHelper createAdbHelper(
      ExecutionContext executionContext,
      AdbOptions adbOptions,
      TargetDeviceOptions targetDeviceOptions) {
    return new AdbHelper(
        adbOptions,
        targetDeviceOptions,
        new ToolchainProviderBuilder()
            .withToolchain(
                AndroidPlatformTarget.DEFAULT_NAME, TestAndroidPlatformTargetFactory.create())
            .build(),
        () -> executionContext,
        true,
        ImmutableList.of());
  }

  /** Verify that null is returned when no devices are present. */
  @Test
  public void testDeviceFilterNoDevices() {
    IDevice[] devices = new IDevice[] {};

    assertNull(basicAdbHelper.filterDevices(devices));
  }

  /** Verify that non-online devices will not appear in result list. */
  @Test
  public void testDeviceFilterOnlineOnly() {
    IDevice[] devices =
        new IDevice[] {
          createEmulator("1", IDevice.DeviceState.OFFLINE),
          createEmulator("2", IDevice.DeviceState.BOOTLOADER),
          createEmulator("3", IDevice.DeviceState.RECOVERY),
          createRealDevice("4", IDevice.DeviceState.OFFLINE),
          createRealDevice("5", IDevice.DeviceState.BOOTLOADER),
          createRealDevice("6", IDevice.DeviceState.RECOVERY),
        };

    assertNull(basicAdbHelper.filterDevices(devices));
  }

  @Test
  public void testEmulatorAddsGenymotionDevices() throws Throwable {
    AdbHelper adbHelper =
        createAdbHelper(createAdbOptions(), new TargetDeviceOptions(true, false, Optional.empty()));

    IDevice[] devices =
        new IDevice[] {
          TestDevice.createRealDevice("foobarblahblah"),
          TestDevice.createRealDevice("192.168.57.101:5555")
        };

    List<IDevice> filtered = adbHelper.filterDevices(devices);

    assertNotNull(filtered);
    assertEquals(1, filtered.size());
    assertEquals("192.168.57.101:5555", filtered.get(0).getSerialNumber());
  }

  @Test
  public void testGenymotionIsntARealDevice() throws Throwable {
    AdbHelper adbHelper =
        createAdbHelper(createAdbOptions(), new TargetDeviceOptions(false, true, Optional.empty()));

    IDevice[] devices =
        new IDevice[] {
          TestDevice.createRealDevice("foobar"), TestDevice.createRealDevice("192.168.57.101:5555")
        };

    List<IDevice> filtered = adbHelper.filterDevices(devices);

    assertNotNull(filtered);
    assertEquals(1, filtered.size());
    assertEquals("foobar", filtered.get(0).getSerialNumber());
  }

  /**
   * Verify that multi-install is not enabled and multiple devices pass the filter null is returned.
   * Also verify that if multiple devices are passing the filter and multi-install mode is enabled
   * they all appear in resulting list.
   */
  @Test
  public void testDeviceFilterMultipleDevices() throws CmdLineException {
    IDevice[] devices =
        new IDevice[] {
          createEmulator("1", IDevice.DeviceState.ONLINE),
          createEmulator("2", IDevice.DeviceState.ONLINE),
          createRealDevice("4", IDevice.DeviceState.ONLINE),
          createRealDevice("5", IDevice.DeviceState.ONLINE)
        };

    List<IDevice> filteredDevicesNoMultiInstall = basicAdbHelper.filterDevices(devices);
    assertNotNull(filteredDevicesNoMultiInstall);
    assertEquals(devices.length, filteredDevicesNoMultiInstall.size());

    AdbHelper myAdbHelper = createAdbHelper(createAdbOptions(true), new TargetDeviceOptions());
    List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
    assertNotNull(filteredDevices);
    assertEquals(devices.length, filteredDevices.size());
  }

  /** Verify that when emulator-only mode is enabled only emulators appear in result. */
  @Test
  public void testDeviceFilterEmulator() throws CmdLineException {
    AdbHelper myAdbHelper =
        createAdbHelper(createAdbOptions(), new TargetDeviceOptions(true, false, Optional.empty()));

    IDevice[] devices =
        new IDevice[] {
          createEmulator("1", IDevice.DeviceState.ONLINE),
          createRealDevice("2", IDevice.DeviceState.ONLINE),
        };

    List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(devices[0], filteredDevices.get(0));
  }

  /** Verify that when real-device-only mode is enabled only real devices appear in result. */
  @Test
  public void testDeviceFilterRealDevices() throws CmdLineException {
    AdbHelper myAdbHelper =
        createAdbHelper(createAdbOptions(), new TargetDeviceOptions(false, true, Optional.empty()));

    IDevice[] devices =
        new IDevice[] {
          createRealDevice("1", IDevice.DeviceState.ONLINE),
          createEmulator("2", IDevice.DeviceState.ONLINE)
        };

    List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(devices[0], filteredDevices.get(0));
  }

  /** Verify that filtering by serial number works. */
  @Test
  public void testDeviceFilterBySerial() throws CmdLineException {
    IDevice[] devices =
        new IDevice[] {
          createRealDevice("1", IDevice.DeviceState.ONLINE),
          createEmulator("2", IDevice.DeviceState.ONLINE),
          createRealDevice("3", IDevice.DeviceState.ONLINE),
          createEmulator("4", IDevice.DeviceState.ONLINE)
        };

    for (int i = 0; i < devices.length; i++) {
      AdbHelper myAdbHelper =
          createAdbHelper(
              createAdbOptions(),
              new TargetDeviceOptions(false, false, Optional.of(devices[i].getSerialNumber())));
      List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
      assertNotNull(filteredDevices);
      assertEquals(1, filteredDevices.size());
      assertSame(devices[i], filteredDevices.get(0));
    }
  }

  /** Verify that filtering by environment variable works. */
  @Test
  public void whenSerialNumberSetInEnvironmentThenCorrectDeviceFound() throws CmdLineException {
    IDevice[] devices =
        new IDevice[] {
          createRealDevice("1", IDevice.DeviceState.ONLINE),
          createEmulator("2", IDevice.DeviceState.ONLINE),
          createRealDevice("3", IDevice.DeviceState.ONLINE),
          createEmulator("4", IDevice.DeviceState.ONLINE)
        };

    for (int i = 0; i < devices.length; i++) {
      AdbHelper myAdbHelper =
          createAdbHelper(
              TestExecutionContext.newBuilder()
                  .setEnvironment(
                      ImmutableMap.of(AdbHelper.SERIAL_NUMBER_ENV, devices[i].getSerialNumber()))
                  .build(),
              createAdbOptions(),
              new TargetDeviceOptions());
      List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
      assertNotNull(filteredDevices);
      assertEquals(1, filteredDevices.size());
      assertSame(devices[i], filteredDevices.get(0));
    }
  }

  /** Verify that if no devices match filters null is returned. */
  @Test
  public void testDeviceFilterNoMatchingDevices() throws CmdLineException {
    IDevice[] devices =
        new IDevice[] {
          createRealDevice("1", IDevice.DeviceState.ONLINE),
          createEmulator("2", IDevice.DeviceState.ONLINE),
          createRealDevice("3", IDevice.DeviceState.ONLINE),
          createEmulator("4", IDevice.DeviceState.ONLINE)
        };

    AdbHelper myAdbHelper =
        createAdbHelper(
            createAdbOptions(),
            new TargetDeviceOptions(false, false, Optional.of("invalid-serial")));
    List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
    assertNull(filteredDevices);
  }

  /** Verify that different combinations of arguments work correctly. */
  @Test
  public void testDeviceFilterCombos() throws CmdLineException {
    TestDevice realDevice1 = createRealDevice("1", IDevice.DeviceState.ONLINE);
    TestDevice realDevice2 = createRealDevice("2", IDevice.DeviceState.ONLINE);
    TestDevice emulator1 = createEmulator("3", IDevice.DeviceState.ONLINE);
    TestDevice emulator2 = createEmulator("4", IDevice.DeviceState.ONLINE);
    IDevice[] devices = new IDevice[] {realDevice1, emulator1, realDevice2, emulator2};

    AdbHelper myAdbHelper;
    // Filter by serial in "real device" mode with serial number for real device.
    myAdbHelper =
        createAdbHelper(
            createAdbOptions(),
            new TargetDeviceOptions(false, true, Optional.of(realDevice1.getSerialNumber())));
    List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(realDevice1, filteredDevices.get(0));

    // Filter by serial in "real device" mode with serial number for emulator.
    myAdbHelper =
        createAdbHelper(
            createAdbOptions(),
            new TargetDeviceOptions(false, true, Optional.of(emulator1.getSerialNumber())));
    filteredDevices = myAdbHelper.filterDevices(devices);
    assertNull(filteredDevices);

    // Filter by serial in "emulator" mode with serial number for real device.
    myAdbHelper =
        createAdbHelper(
            createAdbOptions(),
            new TargetDeviceOptions(true, false, Optional.of(realDevice1.getSerialNumber())));
    filteredDevices = myAdbHelper.filterDevices(devices);
    assertNull(filteredDevices);

    // Filter by serial in "real device" mode with serial number for emulator.
    myAdbHelper =
        createAdbHelper(
            createAdbOptions(),
            new TargetDeviceOptions(true, false, Optional.of(emulator1.getSerialNumber())));
    filteredDevices = myAdbHelper.filterDevices(devices);
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(emulator1, filteredDevices.get(0));

    // Filter in both "real device" mode and "emulator mode".
    myAdbHelper =
        createAdbHelper(
            createAdbOptions(true), new TargetDeviceOptions(true, true, Optional.empty()));
    filteredDevices = myAdbHelper.filterDevices(devices);
    assertNotNull(filteredDevices);
    assertEquals(devices.length, filteredDevices.size());
    for (IDevice device : devices) {
      assertTrue(filteredDevices.contains(device));
    }
  }

  @Test
  public void testQuietDeviceInstall() throws InterruptedException {
    BuckEventBusForTests.CapturingConsoleEventListener listener =
        new BuckEventBusForTests.CapturingConsoleEventListener();
    testContext.getBuckEventBus().register(listener);

    File apk = new File("/some/file.apk");
    AtomicReference<String> apkPath = new AtomicReference<>();

    TestDevice device =
        new TestDevice() {
          @Override
          public void installPackage(String s, boolean b, String... strings) {
            apkPath.set(s);
          }
        };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");

    List<IDevice> deviceList = Lists.newArrayList((IDevice) device);

    AdbHelper adbHelper = createAdbHelper(deviceList);
    adbHelper.adbCall("install apk", (d) -> d.installApkOnDevice(apk, false, true, false), true);

    assertEquals(apk.getAbsolutePath(), apkPath.get());
    assertTrue(listener.getLogMessages().isEmpty());
  }

  @Test
  public void testNonQuietShowsOutput() throws InterruptedException {
    BuckEventBusForTests.CapturingConsoleEventListener listener =
        new BuckEventBusForTests.CapturingConsoleEventListener();
    testContext.getBuckEventBus().register(listener);

    File apk = new File("/some/file.apk");
    AtomicReference<String> apkPath = new AtomicReference<>();

    TestDevice device =
        new TestDevice() {
          @Override
          public void installPackage(String s, boolean b, String... strings) {
            apkPath.set(s);
          }
        };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");

    List<IDevice> deviceList = Lists.newArrayList((IDevice) device);

    AdbHelper adbHelper = createAdbHelper(deviceList);
    adbHelper.adbCall("install apk", (d) -> d.installApkOnDevice(apk, false, false, false), false);

    assertEquals(apk.getAbsolutePath(), apkPath.get());
    MoreAsserts.assertListEquals(
        listener.getLogMessages(),
        ImmutableList.of(
            "Installing apk on serial#1.", "Successfully ran install apk on 1 device(s)"));
  }

  private AdbHelper createAdbHelper(List<IDevice> deviceList) {
    return new AdbHelper(
        createAdbOptions(),
        new TargetDeviceOptions(),
        new ToolchainProviderBuilder()
            .withToolchain(
                AndroidPlatformTarget.DEFAULT_NAME, TestAndroidPlatformTargetFactory.create())
            .build(),
        () -> testContext,
        true,
        ImmutableList.of()) {
      @Override
      public ImmutableList<AndroidDevice> getDevices(boolean quiet) {
        return deviceList
            .stream()
            .map(
                id ->
                    (AndroidDevice)
                        new RealAndroidDevice(testContext.getBuckEventBus(), id, testConsole))
            .collect(ImmutableList.toImmutableList());
      }
    };
  }

  private static AdbOptions createAdbOptions() {
    return createAdbOptions(false);
  }

  private static AdbOptions createAdbOptions(boolean multiInstallMode) {
    return new AdbOptions(
        0,
        multiInstallMode,
        new AndroidBuckConfig(FakeBuckConfig.builder().build(), Platform.detect()).getAdbTimeout());
  }
}
