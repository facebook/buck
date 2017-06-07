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
import com.android.ddmlib.DdmPreferences;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.TestIdentifier;
import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class InstrumentationTestRunner {
  private static final long ADB_CONNECT_TIMEOUT_MS = 5000;
  private static final long ADB_CONNECT_TIME_STEP_MS = ADB_CONNECT_TIMEOUT_MS / 10;

  private final String adbExecutablePath;
  private final String deviceSerial;
  private final String packageName;
  private final String testRunner;
  private final File outputDirectory;
  private final boolean attemptUninstall;
  private final Map<String, String> extraInstrumentationArguments;
  @Nullable private final String instrumentationApkPath;
  @Nullable private final String apkUnderTestPath;

  public InstrumentationTestRunner(
      String adbExecutablePath,
      String deviceSerial,
      String packageName,
      String testRunner,
      File outputDirectory,
      String instrumentationApkPath,
      String apkUnderTestPath,
      boolean attemptUninstall,
      Map<String, String> extraInstrumentationArguments) {
    this.adbExecutablePath = adbExecutablePath;
    this.deviceSerial = deviceSerial;
    this.packageName = packageName;
    this.testRunner = testRunner;
    this.outputDirectory = outputDirectory;
    this.instrumentationApkPath = instrumentationApkPath;
    this.apkUnderTestPath = apkUnderTestPath;
    this.attemptUninstall = attemptUninstall;
    this.extraInstrumentationArguments = extraInstrumentationArguments;
  }

  public static InstrumentationTestRunner fromArgs(String... args) {
    File outputDirectory = null;
    String adbExecutablePath = null;
    String apkUnderTestPath = null;
    String packageName = null;
    String testRunner = null;
    String instrumentationApkPath = null;
    boolean attemptUninstall = false;
    Map<String, String> extraInstrumentationArguments = new HashMap<String, String>();

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
        case "--apk-under-test-path":
          apkUnderTestPath = args[++i];
          break;
        case "--instrumentation-apk-path":
          instrumentationApkPath = args[++i];
          break;
        case "--attempt-uninstall":
          attemptUninstall = true;
          break;
        case "--extra-instrumentation-argument":
          String rawArg = args[++i];
          String[] extraArguments = rawArg.split("=", 2);
          if (extraArguments.length != 2) {
            System.err.printf("Not a valid extra arguments argument: %s\n", rawArg);
            System.exit(1);
          }
          extraInstrumentationArguments.put(extraArguments[0], extraArguments[1]);
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
        adbExecutablePath,
        deviceSerial,
        packageName,
        testRunner,
        outputDirectory,
        instrumentationApkPath,
        apkUnderTestPath,
        attemptUninstall,
        extraInstrumentationArguments);
  }

  public void run() throws Throwable {
    IDevice device = getDevice(this.deviceSerial);

    if (device == null) {
      System.err.printf("Unable to get device/emulator with serial %s", this.deviceSerial);
      System.exit(1);
    }

    if (this.instrumentationApkPath != null) {
      DdmPreferences.setTimeOut(60000);
      device.installPackage(this.instrumentationApkPath, true);
      if (this.apkUnderTestPath != null) {
        device.installPackage(this.apkUnderTestPath, true);
      }
    }

    try {
      final RemoteAndroidTestRunner runner =
          new RemoteAndroidTestRunner(this.packageName, this.testRunner, getDevice(deviceSerial));

      for (Map.Entry<String, String> entry : this.extraInstrumentationArguments.entrySet()) {
        runner.addInstrumentationArg(entry.getKey(), entry.getValue());
      }
      BuckXmlTestRunListener listener = new BuckXmlTestRunListener();
      ITestRunListener trimLineListener =
          new ITestRunListener() {
            /**
             * Before the actual run starts (and after the InstrumentationResultsParser is created),
             * we need to do some reflection magic to make RemoteAndroidTestRunner not trim
             * indentation from lines.
             */
            @Override
            public void testRunStarted(String runName, int testCount) {
              setTrimLine(runner, false);
            }

            @Override
            public void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {}

            @Override
            public void testRunFailed(String errorMessage) {}

            @Override
            public void testStarted(TestIdentifier test) {}

            @Override
            public void testFailed(TestIdentifier test, String trace) {}

            @Override
            public void testAssumptionFailure(TestIdentifier test, String trace) {}

            @Override
            public void testIgnored(TestIdentifier test) {}

            @Override
            public void testEnded(TestIdentifier test, Map<String, String> testMetrics) {}

            @Override
            public void testRunStopped(long elapsedTime) {}
          };

      listener.setReportDir(this.outputDirectory);
      runner.run(trimLineListener, listener);
    } finally {
      if (this.attemptUninstall) {
        // Best effort uninstall from the emulator/device.
        device.uninstallPackage(this.packageName);
      }
    }
  }

  @Nullable
  private IDevice getDevice(String serial) throws InterruptedException {
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
   * Creates connection to adb and waits for this connection to be initialized and receive initial
   * list of devices.
   */
  @Nullable
  @SuppressWarnings("PMD.EmptyCatchBlock")
  private AndroidDebugBridge createAdb() throws InterruptedException {
    AndroidDebugBridge.initIfNeeded(/* clientSupport */ false);
    AndroidDebugBridge adb = AndroidDebugBridge.createBridge(this.adbExecutablePath, false);
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

  // VisibleForTesting
  static void setTrimLine(RemoteAndroidTestRunner runner, boolean value) {
    try {
      Field mParserField = RemoteAndroidTestRunner.class.getDeclaredField("mParser");
      mParserField.setAccessible(true);
      MultiLineReceiver multiLineReceiver = (MultiLineReceiver) mParserField.get(runner);
      multiLineReceiver.setTrimLine(value);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /** We minimize external dependencies, but we'd like to have {@link javax.annotation.Nullable}. */
  @interface Nullable {}
}
