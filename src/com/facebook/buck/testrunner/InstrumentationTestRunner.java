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

package com.facebook.buck.testrunner;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellEnabledDevice;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;

import java.io.File;

public class InstrumentationTestRunner {
  private static final long ADB_CONNECT_TIMEOUT_MS = 5000;
  private static final long ADB_CONNECT_TIME_STEP_MS = ADB_CONNECT_TIMEOUT_MS / 10;

  private String adbExecutablePath;
  private String deviceSerial;
  private String packageName;
  private String testRunner;
  private File outputDirectory;

  public InstrumentationTestRunner(
      String adbExecutablePath,
      String deviceSerial,
      String packageName,
      String testRunner,
      File outputDirectory) {
    this.adbExecutablePath = adbExecutablePath;
    this.deviceSerial = deviceSerial;
    this.packageName = packageName;
    this.testRunner = testRunner;
    this.outputDirectory = outputDirectory;
  }

  public static InstrumentationTestRunner fromArgs(String... args) throws Throwable {
    File outputDirectory = null;
    String adbExecutablePath = null;
    String packageName = null;
    String testRunner = null;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--test-package-name":
          packageName = args[++i];
          break;
        case "--test-runner":
          testRunner = args[++i];
          break;
        case "--output":
          outputDirectory = new File(args[++i]);
          if (!outputDirectory.exists()) {
            System.err.printf("The output directory did not exist: %s\n", outputDirectory);
            System.exit(1);
          }
          break;
        case "--adb-executable-path":
          adbExecutablePath = args[++i];
          break;
      }
    }

    if (packageName == null) {
      System.err.println("Must pass --test-package-name argument.");
      System.exit(1);
    }

    if (testRunner == null) {
      System.err.println("Must pass --test-runner argument.");
      System.exit(1);
    }

    if (outputDirectory == null) {
      System.err.println("Must pass --output argument.");
      System.exit(1);
    }

    if (adbExecutablePath == null) {
      System.err.println("Must pass --adb-executable-path argument.");
      System.exit(1);
    }

    String deviceSerial = System.getProperty("buck.device.id");
    if (deviceSerial == null) {
      System.err.println("Must pass buck.device.id system property.");
      System.exit(1);
    }

    return new InstrumentationTestRunner(
        adbExecutablePath, deviceSerial, packageName, testRunner, outputDirectory);
  }

  public void run() throws Throwable {
    IShellEnabledDevice device = getDevice(this.deviceSerial);

    if (device == null) {
      System.err.printf("Unable to get device/emulator with serial %s", this.deviceSerial);
      System.exit(1);
    }

    RemoteAndroidTestRunner runner = new RemoteAndroidTestRunner(
        this.packageName,
        this.testRunner,
        getDevice(deviceSerial)
    );
    BuckXmlTestRunListener listener = new BuckXmlTestRunListener();
    listener.setReportDir(this.outputDirectory);
    runner.run(listener);
  }

  @Nullable
  private IShellEnabledDevice getDevice(String serial) throws InterruptedException {
    AndroidDebugBridge adb = createAdb();

    if (adb == null) {
      System.err.println("Unable to set up adb.");
      System.exit(1);
    }

    IDevice[] allDevices = adb.getDevices();
    for (IDevice device : allDevices) {
      if (device.getSerialNumber().equals(serial)) {
        return device;
      }
    }
    return null;
  }

  private boolean isAdbInitialized(AndroidDebugBridge adb) {
    return adb.isConnected() && adb.hasInitialDeviceList();
  }

  /**
   * Creates connection to adb and waits for this connection to be initialized
   * and receive initial list of devices.
   */
  @Nullable
  @SuppressWarnings("PMD.EmptyCatchBlock")
  private AndroidDebugBridge createAdb() throws InterruptedException {
    AndroidDebugBridge.initIfNeeded(/* clientSupport */ false);
    AndroidDebugBridge adb =
        AndroidDebugBridge.createBridge(this.adbExecutablePath, false);
    if (adb == null) {
      System.err.println("Failed to connect to adb. Make sure adb server is running.");
      return null;
    }

    long start = System.currentTimeMillis();
    while (!isAdbInitialized(adb)) {
      long timeLeft = start + ADB_CONNECT_TIMEOUT_MS - System.currentTimeMillis();
      if (timeLeft <= 0) {
        break;
      }
      Thread.sleep(ADB_CONNECT_TIME_STEP_MS);
    }
    return isAdbInitialized(adb) ? adb : null;
  }

  /**
   * We minimize external dependencies, but we'd like to have {@link javax.annotation.Nullable}.
   */
  @interface Nullable {}
}
