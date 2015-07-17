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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.InstallException;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Console;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AdbHelperTest {

  private AdbHelper basicAdbHelper;

  @Before
  public void setUp() throws CmdLineException {
    basicAdbHelper = createAdbHelper(
        new AdbOptions(),
        new TargetDeviceOptions());
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

  private TestDevice createDeviceForShellCommandTest(final String output) {
    return new TestDevice() {
      @Override
      public void executeShellCommand(
          String cmd,
          IShellOutputReceiver receiver,
          long timeout,
          TimeUnit timeoutUnit) {
        byte[] outputBytes = output.getBytes();
        receiver.addOutput(outputBytes, 0, outputBytes.length);
        receiver.flush();
      }
    };
  }

  private AdbHelper createAdbHelper(
      AdbOptions adbOptions,
      TargetDeviceOptions targetDeviceOptions)
      throws CmdLineException {
    return createAdbHelper(
        TestExecutionContext.newInstance(),
        adbOptions,
        targetDeviceOptions);
  }

  private AdbHelper createAdbHelper(
      ExecutionContext executionContext,
      AdbOptions adbOptions,
      TargetDeviceOptions targetDeviceOptions)
      throws CmdLineException {
    Console console = new TestConsole();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    return new AdbHelper(
        adbOptions,
        targetDeviceOptions,
        executionContext,
        console,
        eventBus,
        true) {
      @Override
      protected boolean isDeviceTempWritable(IDevice device, String name) {
        return true;
      }
    };
  }

  /**
   * Verify that null is returned when no devices are present.
   */
  @Test
  public void testDeviceFilterNoDevices() throws CmdLineException {
    IDevice[] devices = new IDevice[] { };

    assertNull(basicAdbHelper.filterDevices(devices));
  }

  /**
   * Verify that non-online devices will not appear in result list.
   */
  @Test
  public void testDeviceFilterOnlineOnly() throws CmdLineException {
    IDevice[] devices = new IDevice[] {
        createEmulator("1", IDevice.DeviceState.OFFLINE),
        createEmulator("2", IDevice.DeviceState.BOOTLOADER),
        createEmulator("3", IDevice.DeviceState.RECOVERY),
        createRealDevice("4", IDevice.DeviceState.OFFLINE),
        createRealDevice("5", IDevice.DeviceState.BOOTLOADER),
        createRealDevice("6", IDevice.DeviceState.RECOVERY),
    };

    assertNull(basicAdbHelper.filterDevices(devices));
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

    List<IDevice> filteredDevicesNoMultiInstall = basicAdbHelper.filterDevices(devices);
    assertNotNull(filteredDevicesNoMultiInstall);
    assertEquals(devices.length, filteredDevicesNoMultiInstall.size());

    AdbHelper myAdbHelper = createAdbHelper(
        new AdbOptions(0, true),
        new TargetDeviceOptions());
    List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
    assertNotNull(filteredDevices);
    assertEquals(devices.length, filteredDevices.size());
  }

  /**
   * Verify that when emulator-only mode is enabled only emulators appear in result.
   */
  @Test
  public void testDeviceFilterEmulator() throws CmdLineException {
    AdbHelper myAdbHelper = createAdbHelper(
        new AdbOptions(),
        new TargetDeviceOptions(true, false, null));

    IDevice[] devices = new IDevice[] {
        createEmulator("1", IDevice.DeviceState.ONLINE),
        createRealDevice("2", IDevice.DeviceState.ONLINE),
    };

    List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(devices[0], filteredDevices.get(0));
  }

  /**
   * Verify that when real-device-only mode is enabled only real devices appear in result.
   */
  @Test
  public void testDeviceFilterRealDevices() throws CmdLineException {
    AdbHelper myAdbHelper = createAdbHelper(
        new AdbOptions(),
        new TargetDeviceOptions(false, true, null));

    IDevice[] devices = new IDevice[] {
        createRealDevice("1", IDevice.DeviceState.ONLINE),
        createEmulator("2", IDevice.DeviceState.ONLINE)
    };

    List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
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
      AdbHelper myAdbHelper = createAdbHelper(
          new AdbOptions(),
          new TargetDeviceOptions(false, false, devices[i].getSerialNumber()));
      List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
      assertNotNull(filteredDevices);
      assertEquals(1, filteredDevices.size());
      assertSame(devices[i], filteredDevices.get(0));
    }
  }

  /**
   * Verify that filtering by environment variable works.
   */
  @Test
  public void whenSerialNumberSetInEnvironmentThenCorrectDeviceFound()
      throws CmdLineException {
    IDevice[] devices = new IDevice[] {
        createRealDevice("1", IDevice.DeviceState.ONLINE),
        createEmulator("2", IDevice.DeviceState.ONLINE),
        createRealDevice("3", IDevice.DeviceState.ONLINE),
        createEmulator("4", IDevice.DeviceState.ONLINE)
    };

    for (int i = 0; i < devices.length; i++) {
      AdbHelper myAdbHelper = createAdbHelper(
          TestExecutionContext.newBuilder()
              .setEnvironment(ImmutableMap.of(
                      AdbHelper.SERIAL_NUMBER_ENV,
                      devices[i].getSerialNumber()))
              .build(),
          new AdbOptions(),
          new TargetDeviceOptions());
      List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
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

    AdbHelper myAdbHelper = createAdbHelper(
        new AdbOptions(),
        new TargetDeviceOptions(false, false, "invalid-serial"));
    List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
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

    AdbHelper myAdbHelper;
    // Filter by serial in "real device" mode with serial number for real device.
    myAdbHelper = createAdbHelper(
        new AdbOptions(),
        new TargetDeviceOptions(false, true, realDevice1.getSerialNumber()));
    List<IDevice> filteredDevices = myAdbHelper.filterDevices(
        devices);
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(realDevice1, filteredDevices.get(0));

    // Filter by serial in "real device" mode with serial number for emulator.
    myAdbHelper = createAdbHelper(
        new AdbOptions(),
        new TargetDeviceOptions(false, true, emulator1.getSerialNumber()));
    filteredDevices = myAdbHelper.filterDevices(
        devices);
    assertNull(filteredDevices);

    // Filter by serial in "emulator" mode with serial number for real device.
    myAdbHelper = createAdbHelper(
        new AdbOptions(),
        new TargetDeviceOptions(true, false, realDevice1.getSerialNumber()));
    filteredDevices = myAdbHelper.filterDevices(
        devices);
    assertNull(filteredDevices);

    // Filter by serial in "real device" mode with serial number for emulator.
    myAdbHelper = createAdbHelper(
        new AdbOptions(),
        new TargetDeviceOptions(true, false, emulator1.getSerialNumber()));
    filteredDevices = myAdbHelper.filterDevices(
        devices);
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(emulator1, filteredDevices.get(0));

    // Filter in both "real device" mode and "emulator mode".
    myAdbHelper = createAdbHelper(
        new AdbOptions(0, true),
        new TargetDeviceOptions(true, true, null));
    filteredDevices = myAdbHelper.filterDevices(
        devices);
    assertNotNull(filteredDevices);
    assertEquals(devices.length, filteredDevices.size());
    for (IDevice device : devices) {
      assertTrue(filteredDevices.contains(device));
    }
  }

  /**
   * Verify that successful installation on device results in true.
   */
  @Test
  public void testSuccessfulDeviceInstall() {
    File apk = new File("/some/file.apk");
    final AtomicReference<String> apkPath = new AtomicReference<>();

    TestDevice device = new TestDevice() {
      @Override
      public String installPackage(String s, boolean b, String... strings) throws InstallException {
        apkPath.set(s);
        return null;
      }
    };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");

    assertTrue(basicAdbHelper.installApkOnDevice(device, apk, false));
    assertEquals(apk.getAbsolutePath(), apkPath.get());
  }

  /**
   * Also make sure we're not erroneously parsing "Exception" and "Error".
   */
  @Test
  public void testDeviceStartActivitySuccess() {
    TestDevice device = createDeviceForShellCommandTest(
        "Starting: Intent { cmp=com.example.ExceptionErrorActivity }\r\n");
    assertNull(basicAdbHelper.deviceStartActivity(device, "com.foo/.Activity"));
  }

  @Test
  public void testDeviceStartActivityAmDoesntExist() {
    TestDevice device = createDeviceForShellCommandTest("sh: am: not found\r\n");
    assertNotNull(basicAdbHelper.deviceStartActivity(device, "com.foo/.Activity"));
  }

  @Test
  public void testDeviceStartActivityActivityDoesntExist() {
    String errorLine = "Error: Activity class {com.foo/.Activiqy} does not exist.\r\n";
    TestDevice device = createDeviceForShellCommandTest(
         "Starting: Intent { cmp=com.foo/.Activiqy }\r\n" +
         "Error type 3\r\n" +
         errorLine);
    assertEquals(
        errorLine.trim(),
        basicAdbHelper.deviceStartActivity(device, "com.foo/.Activiy").trim());
  }

  @Test
  public void testDeviceStartActivityException() {
    String errorLine = "java.lang.SecurityException: Permission Denial: " +
        "starting Intent { flg=0x10000000 cmp=com.foo/.Activity } from null " +
        "(pid=27581, uid=2000) not exported from uid 10002\r\n";
    TestDevice device = createDeviceForShellCommandTest(
        "Starting: Intent { cmp=com.foo/.Activity }\r\n" +
        errorLine +
         "  at android.os.Parcel.readException(Parcel.java:1425)\r\n" +
         "  at android.os.Parcel.readException(Parcel.java:1379)\r\n" +
        // (...)
        "  at dalvik.system.NativeStart.main(Native Method)\r\n");
    assertEquals(
        errorLine.trim(),
        basicAdbHelper.deviceStartActivity(device, "com.foo/.Activity").trim());
  }

  /**
   * Verify that if failure reason is returned, installation is marked as failed.
   */
  @Test
  public void testFailedDeviceInstallWithReason() {
    File apk = new File("/some/file.apk");
    TestDevice device = new TestDevice() {
      @Override
      public String installPackage(String s, boolean b, String... strings) throws InstallException {
        return "[SOME_REASON]";
      }
    };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");
    assertFalse(basicAdbHelper.installApkOnDevice(device, apk, false));
  }

  /**
   * Verify that if exception is thrown during installation, installation is marked as failed.
   */
  @Test
  public void testFailedDeviceInstallWithException() {
    File apk = new File("/some/file.apk");

    TestDevice device = new TestDevice() {
      @Override
      public String installPackage(String s, boolean b, String... strings) throws InstallException {
        throw new InstallException("Failed to install on test device.", null);
      }
    };
    device.setSerialNumber("serial#1");
    device.setName("testDevice");
    assertFalse(basicAdbHelper.installApkOnDevice(device, apk, false));
  }

}
