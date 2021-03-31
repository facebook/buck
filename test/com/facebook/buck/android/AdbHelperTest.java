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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.ddmlib.IDevice;
import com.facebook.buck.android.AdbHelper.AndroidDebugBridgeFacade;
import com.facebook.buck.android.device.TargetDeviceOptions;
import com.facebook.buck.android.exopackage.AndroidDevice;
import com.facebook.buck.android.exopackage.RealAndroidDevice;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.engine.impl.TestExecutionContextUtils;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.step.AdbOptions;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AdbHelperTest {
  @Rule public ExpectedException exceptionRule = ExpectedException.none();

  private TestConsole testConsole;
  private ExecutionContext testContext;
  private AdbHelper basicAdbHelper;

  @Before
  public void setUp() {
    testContext = TestExecutionContextUtils.executionContextBuilder().build();
    testConsole = (TestConsole) testContext.getConsole();
    basicAdbHelper = createAdbHelper(createAdbOptions(), new TargetDeviceOptions());
  }

  @After
  public void tearDown() {
    AdbHelper.setDevicesSupplierForTests(Optional.empty());
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

  private AdbHelper createAdbHelper(
      AdbOptions adbOptions, TargetDeviceOptions targetDeviceOptions) {
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
        /* skipMetadataIfNoInstalls= */ false,
        /* alwaysUseJavaAgent */ true);
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
  public void testEmulatorAddsGenymotionDevices() {
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
  public void testGenymotionIsntARealDevice() {
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
  public void testDeviceFilterMultipleDevices() {
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
  public void testDeviceFilterEmulator() {
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
  public void testDeviceFilterRealDevices() {
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
  public void testDeviceFilterBySerial() {
    IDevice[] devices =
        new IDevice[] {
          createRealDevice("1", IDevice.DeviceState.ONLINE),
          createEmulator("2", IDevice.DeviceState.ONLINE),
          createRealDevice("3", IDevice.DeviceState.ONLINE),
          createEmulator("4", IDevice.DeviceState.ONLINE)
        };

    for (IDevice device : devices) {
      AdbHelper myAdbHelper =
          createAdbHelper(
              createAdbOptions(),
              new TargetDeviceOptions(false, false, Optional.of(device.getSerialNumber())));
      List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
      assertNotNull(filteredDevices);
      assertEquals(1, filteredDevices.size());
      assertSame(device, filteredDevices.get(0));
    }
  }

  /** Verify that filtering by environment variable works. */
  @Test
  public void whenSerialNumberSetInEnvironmentThenCorrectDeviceFound() {
    IDevice[] devices =
        new IDevice[] {
          createRealDevice("1", IDevice.DeviceState.ONLINE),
          createEmulator("2", IDevice.DeviceState.ONLINE),
          createRealDevice("3", IDevice.DeviceState.ONLINE),
          createEmulator("4", IDevice.DeviceState.ONLINE)
        };

    for (IDevice device : devices) {
      AdbHelper myAdbHelper =
          createAdbHelper(
              TestExecutionContextUtils.executionContextBuilder()
                  .setEnvironment(
                      ImmutableMap.of(AdbHelper.SERIAL_NUMBER_ENV, device.getSerialNumber()))
                  .build(),
              createAdbOptions(),
              new TargetDeviceOptions());
      List<IDevice> filteredDevices = myAdbHelper.filterDevices(devices);
      assertNotNull(filteredDevices);
      assertEquals(1, filteredDevices.size());
      assertSame(device, filteredDevices.get(0));
    }
  }

  /** Verify that if no devices match filters null is returned. */
  @Test
  public void testDeviceFilterNoMatchingDevices() {
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
  public void testDeviceFilterCombos() {
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

  private BuckEventBusForTests.CapturingEventListener listenToEvents() {
    BuckEventBusForTests.CapturingEventListener listener =
        new BuckEventBusForTests.CapturingEventListener();
    testContext.getBuckEventBus().register(listener);
    return listener;
  }

  private void assertLoggedToConsole(
      BuckEventBusForTests.CapturingEventListener listener, String... messages) {
    MoreAsserts.assertListEquals(
        listener.getConsoleEventLogMessages(), ImmutableList.copyOf(messages));
  }

  @Test
  public void testQuietDeviceInstall() throws InterruptedException {
    BuckEventBusForTests.CapturingEventListener listener = listenToEvents();

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

    List<IDevice> deviceList = Lists.newArrayList(device);

    AdbHelper adbHelper = createAdbHelper(deviceList);
    adbHelper.adbCall("install apk", (d) -> d.installApkOnDevice(apk, false, true, false), true);

    assertEquals(apk.getAbsolutePath(), apkPath.get());
    assertTrue(listener.getConsoleEventLogMessages().isEmpty());
  }

  @Test
  public void testNonQuietShowsOutput() throws InterruptedException {
    BuckEventBusForTests.CapturingEventListener listener = listenToEvents();

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

    List<IDevice> deviceList = Lists.newArrayList(device);

    AdbHelper adbHelper = createAdbHelper(deviceList);
    adbHelper.adbCall("install apk", (d) -> d.installApkOnDevice(apk, false, false, false), false);

    assertEquals(apk.getAbsolutePath(), apkPath.get());
    assertLoggedToConsole(
        listener, "Installing apk on serial#1.", "Successfully ran install apk on 1 device(s)");
  }

  @Test
  public void testGetDevicesShouldLogWhenMultipleDevices() {
    BuckEventBusForTests.CapturingEventListener listener = listenToEvents();

    AdbHelper adbHelper =
        createAdbHelper(
            ImmutableList.of(
                TestDevice.createRealDevice("first"), TestDevice.createRealDevice("second")));

    assertEquals(adbHelper.getDevices(false).size(), 2);
    assertLoggedToConsole(listener, "Found 2 matching devices.\n");
  }

  @Test
  public void testGetDevicesShouldRespectQuietFlag() {
    BuckEventBusForTests.CapturingEventListener listener = listenToEvents();

    AdbHelper adbHelper =
        createAdbHelper(
            ImmutableList.of(
                TestDevice.createRealDevice("first"), TestDevice.createRealDevice("second")));

    assertEquals(adbHelper.getDevices(true).size(), 2);
    assertTrue(listener.getConsoleEventLogMessages().isEmpty());
  }

  @Test
  public void testAdbCallShouldFailWhenNoDevices() throws Exception {
    AdbHelper adbHelper = createAdbHelper(ImmutableList.of());

    exceptionRule.expect(HumanReadableException.class);
    exceptionRule.expectMessage("Didn't find any attached Android devices/emulators.");
    adbHelper.adbCall("dummy", d -> true, true);
  }

  @Test
  public void testGetDevicesShouldReconnectIfFirstConnectionFails() {
    AndroidDebugBridgeFacade adb =
        createFlakyAdb(
            /*connectOnAttempt=*/ 2,
            /*returnDevicesOnAttempt=*/ 0,
            TestDevice.createRealDevice("device"));
    AdbHelper helper = createAdbHelper(adb);

    assertEquals(1, helper.getDevices(true).size());
  }

  @Test
  public void testGetDevicesShouldRetryIfNoDevicesFound() {
    AndroidDebugBridgeFacade adb =
        createFlakyAdb(
            /*connectOnAttempt=*/ 1,
            /*returnDevicesOnAttempt=*/ 3,
            TestDevice.createRealDevice("device"));
    AdbHelper helper = createAdbHelper(adb);

    assertEquals(1, helper.getDevices(true).size());
  }

  @Test
  public void testAdbCallShouldFailForMultipleDevices() throws Exception {
    exceptionRule.expect(HumanReadableException.class);
    exceptionRule.expectMessage("2 devices match specified device filter");

    AndroidDebugBridgeFacade adb =
        createAdbForDevices(TestDevice.createRealDevice("one"), TestDevice.createRealDevice("two"));
    AdbHelper helper = createAdbHelper(adb);
    helper.adbCall("test", d -> true, /*quiet=*/ true);
  }

  @Test
  public void testAdbCallShouldFailForNoDevices() throws Exception {
    exceptionRule.expect(HumanReadableException.class);
    exceptionRule.expectMessage("Didn't find any attached Android devices/emulators.");

    AndroidDebugBridgeFacade adb = createAdbForDevices();
    AdbHelper helper = createAdbHelper(adb);
    helper.adbCall("test", d -> true, /*quiet=*/ true);
  }

  private AndroidDebugBridgeFacade createAdbForDevices(IDevice... devices) {
    return new AndroidDebugBridgeFacade() {
      @Override
      boolean connect() {
        return true;
      }

      @Override
      boolean isConnected() {
        return true;
      }

      @Override
      boolean hasInitialDeviceList() {
        return true;
      }

      @Override
      IDevice[] getDevices() {
        return devices;
      }
    };
  }

  private AndroidDebugBridgeFacade createFlakyAdb(
      int connectOnAttempt, int returnDevicesOnAttempt, IDevice... devices) {
    return new AndroidDebugBridgeFacade() {
      int connectCount = 0;
      int getDevicesCount = 0;

      @Override
      boolean connect() {
        connectCount++;
        return isConnected();
      }

      @Override
      boolean isConnected() {
        return connectCount >= connectOnAttempt;
      }

      @Override
      boolean hasInitialDeviceList() {
        return true;
      }

      @Override
      IDevice[] getDevices() {
        getDevicesCount++;
        if (getDevicesCount >= returnDevicesOnAttempt) {
          return devices;
        }
        return new IDevice[0];
      }
    };
  }

  private AdbHelper createAdbHelper(AndroidDebugBridgeFacade facade) {
    return new AdbHelper(
        createAdbOptions(),
        new TargetDeviceOptions(),
        new ToolchainProviderBuilder()
            .withToolchain(
                AndroidPlatformTarget.DEFAULT_NAME, TestAndroidPlatformTargetFactory.create())
            .build(),
        () -> testContext,
        true,
        /* skipMetadataIfNoInstalls= */ false,
        /* alwaysUseJavaAgent */ true) {
      @Override
      AndroidDebugBridgeFacade createAdb() {
        return facade;
      }
    };
  }

  private AdbHelper createAdbHelper(List<IDevice> deviceList) {
    AdbHelper.setDevicesSupplierForTests(
        Optional.of(
            () ->
                deviceList.stream()
                    .map(
                        id ->
                            (AndroidDevice)
                                new RealAndroidDevice(
                                    testContext.getBuckEventBus(), id, testConsole))
                    .collect(ImmutableList.toImmutableList())));

    return new AdbHelper(
        createAdbOptions(),
        new TargetDeviceOptions(),
        new ToolchainProviderBuilder()
            .withToolchain(
                AndroidPlatformTarget.DEFAULT_NAME, TestAndroidPlatformTargetFactory.create())
            .build(),
        () -> testContext,
        true,
        /* skipMetadataIfNoInstalls= */ false,
        /* alwaysUseJavaAgent */ false);
  }

  private static AdbOptions createAdbOptions() {
    return createAdbOptions(false);
  }

  private static AdbOptions createAdbOptions(boolean multiInstallMode) {
    return new AdbOptions(
        0,
        multiInstallMode,
        new AndroidBuckConfig(FakeBuckConfig.empty(), Platform.detect()).getAdbTimeout());
  }
}
