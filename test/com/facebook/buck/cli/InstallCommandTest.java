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
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;

import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class InstallCommandTest {

  private BuckConfig buckConfig;
  private InstallCommand installCommand;

  @Before
  public void setUp() {
    buckConfig = new FakeBuckConfig();
    installCommand = createInstallCommand();
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

  private TestDevice createDeviceForShellCommandTest(final String output) {
    return new TestDevice() {
      @Override
      public void executeShellCommand(String cmd, IShellOutputReceiver receiver, int timeout) {
        byte[] outputBytes = output.getBytes();
        receiver.addOutput(outputBytes, 0, outputBytes.length);
        receiver.flush();
      }
    };
  }

  private InstallCommand createInstallCommand() {
    Console console = new TestConsole();
    ProjectFilesystem filesystem = new ProjectFilesystem(new File("."));
    AndroidDirectoryResolver androidDirectoryResolver = new FakeAndroidDirectoryResolver();
    KnownBuildRuleTypes buildRuleTypes = KnownBuildRuleTypes.getDefault();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    return new InstallCommand(new CommandRunnerParams(
        console,
        filesystem,
        androidDirectoryResolver,
        buildRuleTypes,
        new InstanceArtifactCacheFactory(artifactCache),
        eventBus,
        BuckTestConstant.PYTHON_INTERPRETER,
        Platform.detect()));
  }

  /**
   * Verify that null is returned when no devices are present.
   */
  @Test
  public void testDeviceFilterNoDevices() throws CmdLineException {
    InstallCommandOptions options = getOptions();
    IDevice[] devices = new IDevice[] { };

    assertNull(installCommand.filterDevices(
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

    assertNull(installCommand.filterDevices(
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
    assertNull(installCommand.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions()));

    options = getOptions(AdbOptions.MULTI_INSTALL_MODE_SHORT_ARG);
    List<IDevice> filteredDevices = installCommand.filterDevices(
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

    List<IDevice> filteredDevices = installCommand.filterDevices(
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

    List<IDevice> filteredDevices = installCommand.filterDevices(
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

      List<IDevice> filteredDevices = installCommand.filterDevices(
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
    List<IDevice> filteredDevices = installCommand.filterDevices(
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
    List<IDevice> filteredDevices = installCommand.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(realDevice1, filteredDevices.get(0));

    // Filter by serial in "real device" mode with serial number for emulator.
    options = getOptions(
        TargetDeviceOptions.SERIAL_NUMBER_SHORT_ARG, emulator1.getSerialNumber(),
        TargetDeviceOptions.DEVICE_MODE_LONG_ARG);
    filteredDevices = installCommand.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNull(filteredDevices);

    // Filter by serial in "emulator" mode with serial number for real device.
    options = getOptions(
        TargetDeviceOptions.SERIAL_NUMBER_SHORT_ARG, realDevice1.getSerialNumber(),
        TargetDeviceOptions.EMULATOR_MODE_SHORT_ARG);
    filteredDevices = installCommand.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNull(filteredDevices);

    // Filter by serial in "real device" mode with serial number for emulator.
    options = getOptions(
        TargetDeviceOptions.SERIAL_NUMBER_SHORT_ARG, emulator1.getSerialNumber(),
        TargetDeviceOptions.EMULATOR_MODE_SHORT_ARG);
    filteredDevices = installCommand.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
    assertNotNull(filteredDevices);
    assertEquals(1, filteredDevices.size());
    assertSame(emulator1, filteredDevices.get(0));

    // Filter in both "real device" mode and "emulator mode".
    options = getOptions(
        TargetDeviceOptions.DEVICE_MODE_LONG_ARG,
        TargetDeviceOptions.EMULATOR_MODE_SHORT_ARG,
        AdbOptions.MULTI_INSTALL_MODE_SHORT_ARG);
    filteredDevices = installCommand.filterDevices(
        devices, options.adbOptions(), options.targetDeviceOptions());
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

    assertTrue(installCommand.installApkOnDevice(device, apk, false));
    assertEquals(apk.getAbsolutePath(), apkPath.get());
  }

  /**
   * Also make sure we're not erroneously parsing "Exception" and "Error".
   */
  @Test
  public void testDeviceStartActivitySuccess() {
    TestDevice device = createDeviceForShellCommandTest(
        "Starting: Intent { cmp=com.example.ExceptionErrorActivity }\r\n");
    assertNull(installCommand.deviceStartActivity(device, "com.foo/.Activity"));
  }

  @Test
  public void testDeviceStartActivityAmDoesntExist() {
    TestDevice device = createDeviceForShellCommandTest("sh: am: not found\r\n");
    assertNotNull(installCommand.deviceStartActivity(device, "com.foo/.Activity"));
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
        installCommand.deviceStartActivity(device, "com.foo/.Activiy").trim());
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
        installCommand.deviceStartActivity(device, "com.foo/.Activity").trim());
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
    assertFalse(installCommand.installApkOnDevice(device, apk, false));
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
    assertFalse(installCommand.installApkOnDevice(device, apk, false));
  }
}
