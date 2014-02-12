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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.IDevice;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Console;

import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.util.List;

public class AdbHelperTest {

  private BuckConfig buckConfig;
  private AdbHelper adbHelper;

  @Before
  public void setUp() {
    buckConfig = new FakeBuckConfig();
    adbHelper = createAdbHelper();
  }

  private InstallCommandOptions getOptions(String...args) throws CmdLineException {
    InstallCommandOptions options = new InstallCommandOptions(buckConfig);
    new CmdLineParserAdditionalOptions(options).parseArgument(args);
    return options;
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

  private AdbHelper createAdbHelper() {
    Console console = new TestConsole();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    return new AdbHelper(console, eventBus);
  }

  /**
   * Verify that null is returned when no devices are present.
   */
  @Test
  public void testDeviceFilterNoDevices() throws CmdLineException {
    InstallCommandOptions options = getOptions();
    IDevice[] devices = new IDevice[] { };

    assertNull(adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions()));
  }

  /**
   * Verify that non-online devices will not appear in result list.
   */
  @Test
  public void testDeviceFilterOnlineOnly() throws CmdLineException {
    InstallCommandOptions options = getOptions();
    IDevice[] devices = new IDevice[] {
        createEmulator("1", IDevice.DeviceState.OFFLINE),
        createEmulator("2", IDevice.DeviceState.BOOTLOADER),
        createEmulator("3", IDevice.DeviceState.RECOVERY),
        createRealDevice("4", IDevice.DeviceState.OFFLINE),
        createRealDevice("5", IDevice.DeviceState.BOOTLOADER),
        createRealDevice("6", IDevice.DeviceState.RECOVERY),
    };

    assertNull(
        adbHelper.filterDevices(
            devices, options.adbOptions(), options.targetDeviceOptions()));
  }

  /**
   * Verify that multi-install is not enabled and multiple devices
   * pass the filter null is returned. Also verify that if multiple
   * devices are passing the filter and multi-install mode is enabled
   * they all appear in resulting list.
   */
  @Test
  public void testDeviceFilterMultipleDevices() throws CmdLineException {
    IDevice[] devices = new IDevice[] {
        createEmulator("1", IDevice.DeviceState.ONLINE),
        createEmulator("2", IDevice.DeviceState.ONLINE),
        createRealDevice("4", IDevice.DeviceState.ONLINE),
        createRealDevice("5", IDevice.DeviceState.ONLINE)
    };

    InstallCommandOptions options = getOptions();
    assertNull(adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions()));

    options = getOptions(AdbOptions.MULTI_INSTALL_MODE_SHORT_ARG);
    List<IDevice> filteredDevices = adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNotNull(filteredDevices);
    assertEquals(devices.length, filteredDevices.size());
  }

  /**
   * Verify that when emulator-only mode is enabled only emulators appear in result.
   */
  @Test
  public void testDeviceFilterEmulator() throws CmdLineException {
    InstallCommandOptions options = getOptions(TargetDeviceOptions.EMULATOR_MODE_SHORT_ARG);

    IDevice[] devices = new IDevice[] {
        createEmulator("1", IDevice.DeviceState.ONLINE),
        createRealDevice("2", IDevice.DeviceState.ONLINE),
    };

    List<IDevice> filteredDevices = adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(devices[0], filteredDevices.get(0));
  }

  /**
   * Verify that when real-device-only mode is enabled only real devices appear in result.
   */
  @Test
  public void testDeviceFilterRealDevices() throws CmdLineException {
    InstallCommandOptions options = getOptions(TargetDeviceOptions.DEVICE_MODE_LONG_ARG);

    IDevice[] devices = new IDevice[] {
        createRealDevice("1", IDevice.DeviceState.ONLINE),
        createEmulator("2", IDevice.DeviceState.ONLINE)
    };

    List<IDevice> filteredDevices = adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(devices[0], filteredDevices.get(0));
  }

  /**
   * Verify that filtering by serial number works.
   */
  @Test
  public void testDeviceFilterBySerial() throws CmdLineException {
    IDevice[] devices = new IDevice[] {
        createRealDevice("1", IDevice.DeviceState.ONLINE),
        createEmulator("2", IDevice.DeviceState.ONLINE),
        createRealDevice("3", IDevice.DeviceState.ONLINE),
        createEmulator("4", IDevice.DeviceState.ONLINE)
    };

    for (int i = 0; i < devices.length; i++) {
      InstallCommandOptions options = getOptions(TargetDeviceOptions.SERIAL_NUMBER_SHORT_ARG,
          devices[i].getSerialNumber());

      List<IDevice> filteredDevices = adbHelper.filterDevices(
          devices, options.adbOptions(), options.targetDeviceOptions());
      assertNotNull(filteredDevices);
      assertEquals(1, filteredDevices.size());
      assertSame(devices[i], filteredDevices.get(0));
    }
  }

  /**
   * Verify that if no devices match filters null is returned.
   */
  @Test
  public void testDeviceFilterNoMatchingDevices() throws CmdLineException {
    IDevice[] devices = new IDevice[] {
        createRealDevice("1", IDevice.DeviceState.ONLINE),
        createEmulator("2", IDevice.DeviceState.ONLINE),
        createRealDevice("3", IDevice.DeviceState.ONLINE),
        createEmulator("4", IDevice.DeviceState.ONLINE)
    };

    InstallCommandOptions options = getOptions(
          TargetDeviceOptions.SERIAL_NUMBER_SHORT_ARG, "invalid-serial");
    List<IDevice> filteredDevices = adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNull(filteredDevices);
  }

  /**
   * Verify that different combinations of arguments work correctly.
   */
  @Test
  public void testDeviceFilterCombos() throws CmdLineException {
    TestDevice realDevice1 = createRealDevice("1", IDevice.DeviceState.ONLINE);
    TestDevice realDevice2 = createRealDevice("2", IDevice.DeviceState.ONLINE);
    TestDevice emulator1 = createEmulator("3", IDevice.DeviceState.ONLINE);
    TestDevice emulator2 = createEmulator("4", IDevice.DeviceState.ONLINE);
    IDevice[] devices = new IDevice[] {
        realDevice1,
        emulator1,
        realDevice2,
        emulator2
    };

    // Filter by serial in "real device" mode with serial number for real device.
    InstallCommandOptions options = getOptions(
        TargetDeviceOptions.SERIAL_NUMBER_SHORT_ARG, realDevice1.getSerialNumber(),
        TargetDeviceOptions.DEVICE_MODE_LONG_ARG);
    List<IDevice> filteredDevices = adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(realDevice1, filteredDevices.get(0));

    // Filter by serial in "real device" mode with serial number for emulator.
    options = getOptions(
        TargetDeviceOptions.SERIAL_NUMBER_SHORT_ARG, emulator1.getSerialNumber(),
        TargetDeviceOptions.DEVICE_MODE_LONG_ARG);
    filteredDevices = adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNull(filteredDevices);

    // Filter by serial in "emulator" mode with serial number for real device.
    options = getOptions(
        TargetDeviceOptions.SERIAL_NUMBER_SHORT_ARG, realDevice1.getSerialNumber(),
        TargetDeviceOptions.EMULATOR_MODE_SHORT_ARG);
    filteredDevices = adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNull(filteredDevices);

    // Filter by serial in "real device" mode with serial number for emulator.
    options = getOptions(
        TargetDeviceOptions.SERIAL_NUMBER_SHORT_ARG, emulator1.getSerialNumber(),
        TargetDeviceOptions.EMULATOR_MODE_SHORT_ARG);
    filteredDevices = adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(emulator1, filteredDevices.get(0));

    // Filter in both "real device" mode and "emulator mode".
    options = getOptions(
        TargetDeviceOptions.DEVICE_MODE_LONG_ARG,
        TargetDeviceOptions.EMULATOR_MODE_SHORT_ARG,
        AdbOptions.MULTI_INSTALL_MODE_SHORT_ARG);
    filteredDevices = adbHelper.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNotNull(filteredDevices);
    assertEquals(devices.length, filteredDevices.size());
    for (IDevice device : devices) {
      assertTrue(filteredDevices.contains(device));
    }
  }
}
