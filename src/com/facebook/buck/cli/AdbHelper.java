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

import static com.facebook.buck.util.concurrent.MoreExecutors.newMultiThreadExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultAndroidManifestReader;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.TriState;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

/**
 * Helper for executing commands over ADB, especially for multiple devices.
 */
public class AdbHelper {

  private static final long ADB_CONNECT_TIMEOUT_MS = 5000;
  private static final long ADB_CONNECT_TIME_STEP_MS = ADB_CONNECT_TIMEOUT_MS / 10;

  // Taken from ddms source code.
  public static final int INSTALL_TIMEOUT = 2 * 60 * 1000; // 2 min
  public static final int GETPROP_TIMEOUT = 2 * 1000; // 2 seconds

  private final Console console;
  private final BuckEventBus buckEventBus;

  public AdbHelper(Console console, BuckEventBus buckEventBus) {
    this.console = console;
    this.buckEventBus = buckEventBus;
  }

  private BuckEventBus getBuckEventBus() {
    return buckEventBus;
  }

  /**
   * Returns list of devices that pass the filter. If there is an invalid combination or no
   * devices are left after filtering this function prints an error and returns null.
   */
  @Nullable
  @VisibleForTesting
  List<IDevice> filterDevices(IDevice[] allDevices,
                              AdbOptions adbOptions,
                              TargetDeviceOptions deviceOptions) {
    if (allDevices.length == 0) {
      console.printBuildFailure("No devices are found.");
      return null;
    }

    List<IDevice> devices = Lists.newArrayList();
    TriState emulatorsOnly = TriState.UNSPECIFIED;
    if (deviceOptions.isEmulatorsOnlyModeEnabled() && adbOptions.isMultiInstallModeEnabled()) {
      emulatorsOnly = TriState.UNSPECIFIED;
    } else if (deviceOptions.isEmulatorsOnlyModeEnabled()) {
      emulatorsOnly = TriState.TRUE;
    } else if (deviceOptions.isRealDevicesOnlyModeEnabled()) {
      emulatorsOnly = TriState.FALSE;
    }

    int onlineDevices = 0;
    for (IDevice device : allDevices) {
      boolean passed = false;
      if (device.isOnline()) {
        onlineDevices++;

        boolean serialMatches = true;
        if (deviceOptions.hasSerialNumber()) {
          serialMatches = device.getSerialNumber().equals(deviceOptions.getSerialNumber());
        }

        boolean deviceTypeMatches;
        if (emulatorsOnly.isSet()) {
          // Only devices of specific type are accepted:
          // either real devices only or emulators only.
          deviceTypeMatches = (emulatorsOnly.asBoolean() == device.isEmulator());
        } else {
          // All online devices match.
          deviceTypeMatches = true;
        }
        passed = serialMatches && deviceTypeMatches;
      }

      if (passed) {
        devices.add(device);
      }
    }

    // Filtered out all devices.
    if (onlineDevices == 0) {
      console.printBuildFailure("No devices are found.");
      return null;
    }

    if (devices.isEmpty()) {
      console.printBuildFailure(String.format(
          "Found %d connected device(s), but none of them matches specified filter.", onlineDevices
      ));
      return null;
    }

    // Found multiple devices but multi-install mode is not enabled.
    if (!adbOptions.isMultiInstallModeEnabled() && devices.size() > 1) {
      console.printBuildFailure(
          String.format("%d device(s) matches specified device filter (1 expected).\n" +
                        "Either disconnect other devices or enable multi-install mode (%s).",
                         devices.size(), AdbOptions.MULTI_INSTALL_MODE_SHORT_ARG));
      return null;
    }

    // Report if multiple devices are matching the filter.
    if (devices.size() > 1) {
      console.getStdOut().printf("Found " + devices.size() + " matching devices.\n");
    }
    return devices;
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
  private AndroidDebugBridge createAdb(ExecutionContext context) {
    try {
      AndroidDebugBridge.init(/* clientSupport */ false);
    } catch (IllegalStateException ex) {
      // ADB was already initialized, we're fine, so just ignore.
    }

    AndroidDebugBridge adb = null;
    if (context != null) {
      adb = AndroidDebugBridge.createBridge(context.getPathToAdbExecutable(), false);
    } else {
      adb = AndroidDebugBridge.createBridge();
    }
    if (adb == null) {
      console.printBuildFailure("Failed to connect to adb. Make sure adb server is running.");
      return null;
    }

    long start = System.currentTimeMillis();
    while (!isAdbInitialized(adb)) {
      long timeLeft = start + ADB_CONNECT_TIMEOUT_MS - System.currentTimeMillis();
      if (timeLeft <= 0) {
        break;
      }
      try {
        Thread.sleep(ADB_CONNECT_TIME_STEP_MS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return isAdbInitialized(adb) ? adb : null;
  }

  /**
   * Execute an {@link AdbCallable} for all matching devices. This functions performs device
   * filtering based on three possible arguments:
   *
   *  -e (emulator-only) - only emulators are passing the filter
   *  -d (device-only) - only real devices are passing the filter
   *  -s (serial) - only device/emulator with specific serial number are passing the filter
   *
   *  If more than one device matches the filter this function will fail unless multi-install
   *  mode is enabled (-x). This flag is used as a marker that user understands that multiple
   *  devices will be used to install the apk if needed.
   */
  public boolean adbCall(AdbOptions options,
                         TargetDeviceOptions deviceOptions,
                         ExecutionContext context,
                         AdbCallable adbCallable,
                         BuckConfig buckConfig) {

    // Initialize adb connection.
    AndroidDebugBridge adb = createAdb(context);
    if (adb == null) {
      console.printBuildFailure("Failed to create adb connection.");
      return false;
    }

    // Build list of matching devices.
    List<IDevice> devices = filterDevices(adb.getDevices(), options, deviceOptions);
    if (devices == null) {
      if (buckConfig.getRestartAdbOnFailure()) {
        console.printErrorText("No devices found with adb, restarting adb-server.");
        adb.restart();
        devices = filterDevices(adb.getDevices(), options, deviceOptions);
      }

      if (devices == null) {
        return false;
      }
    }

    int adbThreadCount = options.getAdbThreadCount();
    if (adbThreadCount <= 0) {
      adbThreadCount = devices.size();
    }

    // Start executions on all matching devices.
    List<ListenableFuture<Boolean>> futures = Lists.newArrayList();
    ListeningExecutorService executorService =
        listeningDecorator(newMultiThreadExecutor(getClass().getSimpleName(), adbThreadCount));
    for (final IDevice device : devices) {
      futures.add(executorService.submit(adbCallable.forDevice(device)));
    }

    // Wait for all executions to complete or fail.
    List<Boolean> results = null;
    try {
      results = Futures.allAsList(futures).get();
    } catch (ExecutionException ex) {
      console.printBuildFailure("Failed: " + adbCallable);
      ex.printStackTrace(console.getStdErr());
      return false;
    } catch (InterruptedException ex) {
      console.printBuildFailure("Interrupted.");
      ex.printStackTrace(console.getStdErr());
      return false;
    } finally {
      executorService.shutdownNow();
    }

    int successCount = 0;
    for (Boolean result : results) {
      if (result) {
        successCount++;
      }
    }
    int failureCount = results.size() - successCount;

    // Report results.
    if (successCount > 0) {
      console.printSuccess(
          String.format("Succesfully ran %s on %d device(s)", adbCallable, successCount));
    }
    if (failureCount > 0) {
      console.printBuildFailure(
          String.format("Failed to %s on %d device(s).", adbCallable, failureCount));
    }

    return failureCount == 0;
  }

  /**
   * Base class for commands to be run against an {@link com.android.ddmlib.IDevice IDevice}.
   */
  public abstract static class AdbCallable {

    /**
     * Perform the actions specified by this {@code AdbCallable} and return true on success.
     * @param device the {@link com.android.ddmlib.IDevice IDevice} to run against
     * @return {@code true} if the command succeeded.
     */
    public abstract boolean call(IDevice device) throws Exception;

    /**
     * Wraps this as a {@link java.util.concurrent.Callable Callable&lt;Boolean&gt;} whose
     * {@link Callable#call() call()} method calls
     * {@link AdbHelper.AdbCallable#call(IDevice) call(IDevice)} against the specified
     * device.
     * @param device the {@link com.android.ddmlib.IDevice IDevice} to run against.
     * @return a {@code Callable}
     */
    public Callable<Boolean> forDevice(final IDevice device) {
      return new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          return AdbCallable.this.call(device);
        }
        @Override
        public String toString() {
          return AdbCallable.this.toString();
        }
      };
    }
  }

  /**
   * Implementation of {@link com.android.ddmlib.IShellOutputReceiver} with helper functions to
   * parse output lines and figure out if a call to
   * {@link com.android.ddmlib.IDevice#executeShellCommand(String,
   * com.android.ddmlib.IShellOutputReceiver)} succeeded.
   */
  // Should be made private in a follow-up diff.
  public abstract static class ErrorParsingReceiver extends MultiLineReceiver {

    private String errorMessage = null;

    /**
     * Look for an error message in {@code line}.
     * @param line
     * @return an error message if {@code line} is indicative of an error, {@code null} otherwise.
     */
    protected abstract String matchForError(String line);

    /**
     * Look for a message indicating success - the error message is reset if this returns
     * {@code true}.
     * @param line
     * @return {@code true} if this line indicates success.
     */
    protected boolean matchForSuccess(String line) {
      return false;
    }

    @Override
    public void processNewLines(String[] lines) {
        for (String line : lines) {
            if (line.length() > 0) {
                if (matchForSuccess(line)) {
                    errorMessage = null;
                } else {
                    String err = matchForError(line);
                    if (err != null) {
                      errorMessage = err;
                    }
                }
            }
        }
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    public String getErrorMessage() {
       return errorMessage;
    }
  }

  /**
   * Uninstall apk from all matching devices.
   *
   * @see InstallCommand#installApk(
     com.facebook.buck.rules.InstallableApk, InstallCommandOptions, ExecutionContext)
   */
  public boolean uninstallApk(final String packageName,
      final AdbOptions adbOptions,
      final TargetDeviceOptions deviceOptions,
      final UninstallCommandOptions.UninstallOptions uninstallOptions,
      ExecutionContext context,
      BuckConfig buckConfig) {
    getBuckEventBus().post(UninstallEvent.started(packageName));
    boolean success = adbCall(
        adbOptions, deviceOptions, context, new AdbHelper.AdbCallable() {
      @Override
      public boolean call(IDevice device) throws Exception {
        return uninstallApkFromDevice(device, packageName, uninstallOptions.shouldKeepUserData());
      }

      @Override
      public String toString() {
        return "uninstall apk";
      }
    },
        buckConfig);
    getBuckEventBus().post(UninstallEvent.finished(packageName, success));
    return success;
  }

  /**
   * Uninstalls apk from specific device. Reports success or failure to console.
   * It's currently here because it's used both by {@link InstallCommand} and
   * {@link UninstallCommand}.
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  private boolean uninstallApkFromDevice(IDevice device, String packageName, boolean keepData) {
    String name;
    if (device.isEmulator()) {
      name = device.getSerialNumber() + " (" + device.getAvdName() + ")";
    } else {
      name = device.getSerialNumber();
      String model = device.getProperty("ro.product.model");
      if (model != null) {
        name += " (" + model + ")";
      }
    }

    PrintStream stdOut = console.getStdOut();
    stdOut.printf("Removing apk from %s.\n", name);
    try {
      long start = System.currentTimeMillis();
      String reason = deviceUninstallPackage(device, packageName, keepData);
      long end = System.currentTimeMillis();

      if (reason != null) {
        console.printBuildFailure(
            String.format("Failed to uninstall apk from %s: %s.", name, reason));
        return false;
      }

      long delta = end - start;
      stdOut.printf("Uninstalled apk from %s in %d.%03ds.\n", name, delta / 1000, delta % 1000);
      return true;

    } catch (InstallException ex) {
      console.printBuildFailure(String.format("Failed to uninstall apk from %s.", name));
      ex.printStackTrace(console.getStdErr());
      return false;
    }
  }

  /**
   * Modified version of <a href="http://fburl.com/8840769">Device.uninstallPackage()</a>.
   *
   * @param device an {@link IDevice}
   * @param packageName application package name
   * @param keepData  true if user data is to be kept
   * @return error message or null if successful
   * @throws InstallException
   */
  private String deviceUninstallPackage(IDevice device,
      String packageName,
      boolean keepData) throws InstallException {
    try {
      AdbHelper.ErrorParsingReceiver receiver = new AdbHelper.ErrorParsingReceiver() {
        @Override
        protected String matchForError(String line) {
          return line.toLowerCase().contains("failure") ? line : null;
        }
      };
      device.executeShellCommand(
          "pm uninstall " + (keepData ? "-k " : "") + packageName,
          receiver,
          AdbHelper.INSTALL_TIMEOUT);
      return receiver.getErrorMessage();
    } catch (TimeoutException e) {
      throw new InstallException(e);
    } catch (AdbCommandRejectedException e) {
      throw new InstallException(e);
    } catch (ShellCommandUnresponsiveException e) {
      throw new InstallException(e);
    } catch (IOException e) {
      throw new InstallException(e);
    }
  }

  public static String tryToExtractPackageNameFromManifest(InstallableApk androidBinaryRule) {
    Path pathToManifest = androidBinaryRule.getManifestPath();

    // Note that the file may not exist if AndroidManifest.xml is a generated file
    // and the rule has not been built yet.
    if (!Files.isRegularFile(pathToManifest)) {
      throw new HumanReadableException(
          "Manifest file %s does not exist, so could not extract package name.",
          pathToManifest);
    }

    try {
      return DefaultAndroidManifestReader.forPath(pathToManifest).getPackage();
    } catch (IOException e) {
      throw new HumanReadableException("Could not extract package name from %s", pathToManifest);
    }
  }
}
