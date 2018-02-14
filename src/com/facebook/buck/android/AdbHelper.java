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

import static com.facebook.buck.util.concurrent.MostExecutors.newMultiThreadExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.DdmPreferences;
import com.android.ddmlib.IDevice;
import com.facebook.buck.android.exopackage.AndroidDevice;
import com.facebook.buck.android.exopackage.AndroidDevicesHelper;
import com.facebook.buck.android.exopackage.ExopackageInfo;
import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.android.exopackage.RealAndroidDevice;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StartActivityEvent;
import com.facebook.buck.event.UninstallEvent;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.InterruptionFailedException;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Helper for executing commands over ADB, especially for multiple devices. */
public class AdbHelper implements AndroidDevicesHelper {
  private static final long ADB_CONNECT_TIMEOUT_MS = 5000;
  private static final long ADB_CONNECT_TIME_STEP_MS = ADB_CONNECT_TIMEOUT_MS / 10;

  /** Pattern that matches safe package names. (Must be a full string match). */
  public static final Pattern PACKAGE_NAME_PATTERN = Pattern.compile("[\\w.-]+");

  private static Optional<Supplier<ImmutableList<AndroidDevice>>> devicesSupplierForTests =
      Optional.empty();

  /**
   * If this environment variable is set, the device with the specified serial number is targeted.
   * The -s option overrides this.
   */
  static final String SERIAL_NUMBER_ENV = "ANDROID_SERIAL";

  /**
   * The next port number to use for communicating with the agent on a device. This resets for every
   * instance of AdbHelper, but is incremented for every device on every call to adbCall().
   */
  private final AtomicInteger nextAgentPort = new AtomicInteger(2828);

  private final AdbOptions options;
  private final TargetDeviceOptions deviceOptions;
  private final ToolchainProvider toolchainProvider;
  private final Supplier<ExecutionContext> contextSupplier;
  private final boolean restartAdbOnFailure;
  private final ImmutableList<String> rapidInstallTypes;
  private final Supplier<ImmutableList<AndroidDevice>> devicesSupplier;

  @Nullable private ListeningExecutorService executorService = null;

  public AdbHelper(
      AdbOptions adbOptions,
      TargetDeviceOptions deviceOptions,
      ToolchainProvider toolchainProvider,
      Supplier<ExecutionContext> contextSupplier,
      boolean restartAdbOnFailure,
      ImmutableList<String> rapidInstallTypes) {
    this.options = adbOptions;
    this.deviceOptions = deviceOptions;
    this.toolchainProvider = toolchainProvider;
    this.contextSupplier = contextSupplier;
    this.restartAdbOnFailure = restartAdbOnFailure;
    this.rapidInstallTypes = rapidInstallTypes;
    this.devicesSupplier = MoreSuppliers.memoize(this::getDevicesImpl);
  }

  @VisibleForTesting
  public static void setDevicesSupplierForTests(
      Optional<Supplier<ImmutableList<AndroidDevice>>> devicesSupplierForTests) {
    AdbHelper.devicesSupplierForTests = devicesSupplierForTests;
  }

  @Override
  public ImmutableList<AndroidDevice> getDevices(boolean quiet) throws InterruptedException {
    ImmutableList<AndroidDevice> devices = devicesSupplier.get();
    if (!quiet && devices.size() > 1) {
      // Report if multiple devices are matching the filter.
      printMessage("Found " + devices.size() + " matching devices.\n");
    }
    return devices;
  }

  /**
   * Execute an {@link AdbDeviceCallable} for all matching devices. This functions performs device
   * filtering based on three possible arguments:
   *
   * <p>-e (emulator-only) - only emulators are passing the filter -d (device-only) - only real
   * devices are passing the filter -s (serial) - only device/emulator with specific serial number
   * are passing the filter
   *
   * <p>If more than one device matches the filter this function will fail unless multi-install mode
   * is enabled (-x). This flag is used as a marker that user understands that multiple devices will
   * be used to install the apk if needed.
   */
  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Override
  public synchronized boolean adbCall(String description, AdbDeviceCallable func, boolean quiet)
      throws InterruptedException {
    List<AndroidDevice> devices;

    try (SimplePerfEvent.Scope ignored =
        SimplePerfEvent.scope(getBuckEventBus(), "set_up_adb_call")) {
      devices = getDevices(quiet);
      if (devices.size() == 0) {
        return false;
      }
    }

    // Start executions on all matching devices.
    List<ListenableFuture<Boolean>> futures = new ArrayList<>();
    for (final AndroidDevice device : devices) {
      futures.add(
          getExecutorService()
              .submit(
                  () -> {
                    try (SimplePerfEvent.Scope ignored =
                        SimplePerfEvent.scope(
                            getBuckEventBus(),
                            PerfEventId.of("adbCall " + description),
                            "device_serial",
                            device.getSerialNumber())) {
                      return func.apply(device);
                    }
                  }));
    }

    // Wait for all executions to complete or fail.
    List<Boolean> results = null;
    try {
      results = Futures.allAsList(futures).get();
    } catch (ExecutionException ex) {
      printError("Failed: " + description);
      printStacktrace(ex);
      return false;
    } catch (InterruptedException e) {
      try {
        Futures.allAsList(futures).cancel(true);
      } catch (CancellationException ignored) {
        // Rethrow original InterruptedException instead.
      }
      Threads.interruptCurrentThread();
      throw e;
    }

    int successCount = 0;
    for (Boolean result : results) {
      if (result) {
        successCount++;
      }
    }
    int failureCount = results.size() - successCount;

    // Report results.
    if (successCount > 0 && !quiet) {
      printSuccess(String.format("Successfully ran %s on %d device(s)", description, successCount));
    }
    if (failureCount > 0) {
      printError(String.format("Failed to %s on %d device(s).", description, failureCount));
    }

    return failureCount == 0;
  }

  private synchronized ListeningExecutorService getExecutorService() {
    if (executorService != null) {
      return executorService;
    }
    int deviceCount;
    try {
      deviceCount = getDevices(true).size();
    } catch (InterruptedException e) {
      throw new BuckUncheckedExecutionException(e);
    }
    int adbThreadCount = options.getAdbThreadCount();
    if (adbThreadCount <= 0) {
      adbThreadCount = deviceCount;
    }
    adbThreadCount = Math.min(deviceCount, adbThreadCount);
    executorService =
        listeningDecorator(
            newMultiThreadExecutor(
                new CommandThreadFactory(getClass().getSimpleName()), adbThreadCount));
    return executorService;
  }

  private void printMessage(String message) {
    getBuckEventBus().post(ConsoleEvent.info(message));
  }

  private void printSuccess(String successMessage) {
    Ansi ansi = contextSupplier.get().getAnsi();
    getBuckEventBus().post(ConsoleEvent.info(ansi.asHighlightedSuccessText(successMessage)));
  }

  private void printError(String failureMessage) {
    getBuckEventBus().post(ConsoleEvent.severe(failureMessage));
  }

  private void printStacktrace(ExecutionException ex) {
    // TODO(cjhopman): This message should not be sent directly to the console. Will probably
    // require making adbCall() propagate exceptions correctly.
    ex.printStackTrace(getConsole().getStdErr());
  }

  @Override
  public boolean installApk(
      SourcePathResolver pathResolver,
      HasInstallableApk hasInstallableApk,
      boolean installViaSd,
      boolean quiet,
      @Nullable String processName)
      throws InterruptedException {
    InstallEvent.Started started = InstallEvent.started(hasInstallableApk.getBuildTarget());
    if (!quiet) {
      getBuckEventBus().post(started);
    }
    boolean success;
    Optional<ExopackageInfo> exopackageInfo = hasInstallableApk.getApkInfo().getExopackageInfo();
    if (exopackageInfo.isPresent()) {
      // TODO(dreiss): Support SD installation.
      success = installApkExopackage(pathResolver, hasInstallableApk, quiet, processName);
    } else {
      success = installApkDirectly(pathResolver, hasInstallableApk, installViaSd, quiet);
    }
    if (!quiet) {
      getBuckEventBus()
          .post(
              InstallEvent.finished(
                  started,
                  success,
                  Optional.empty(),
                  Optional.of(
                      AdbHelper.tryToExtractPackageNameFromManifest(
                          pathResolver, hasInstallableApk.getApkInfo()))));
    }
    return success;
  }

  @Override
  public void startActivity(
      SourcePathResolver pathResolver,
      HasInstallableApk hasInstallableApk,
      @Nullable String activity,
      boolean waitForDebugger)
      throws IOException, InterruptedException {

    // Might need the package name and activities from the AndroidManifest.
    Path pathToManifest =
        pathResolver.getAbsolutePath(hasInstallableApk.getApkInfo().getManifestPath());
    AndroidManifestReader reader =
        DefaultAndroidManifestReader.forPath(
            hasInstallableApk.getProjectFilesystem().resolve(pathToManifest));

    if (activity == null) {
      // Get list of activities that show up in the launcher.
      List<String> launcherActivities = reader.getLauncherActivities();

      // Sanity check.
      if (launcherActivities.isEmpty()) {
        throw new HumanReadableException("No launchable activities found.");
      } else if (launcherActivities.size() > 1) {
        throw new HumanReadableException("Default activity is ambiguous.");
      }

      // Construct a component for the '-n' argument of 'adb shell am start'.
      activity = reader.getPackage() + "/" + launcherActivities.get(0);
    } else if (!activity.contains("/")) {
      // If no package name was provided, assume the one in the manifest.
      activity = reader.getPackage() + "/" + activity;
    }

    final String activityToRun = activity;

    printMessage(String.format("Starting activity %s...", activityToRun));

    StartActivityEvent.Started started =
        StartActivityEvent.started(hasInstallableApk.getBuildTarget(), activityToRun);
    getBuckEventBus().post(started);
    try {
      adbCallOrThrow(
          "start activity",
          (device) -> {
            ((RealAndroidDevice) device).deviceStartActivity(activityToRun, waitForDebugger);
            return true;
          },
          false);
      getBuckEventBus().post(StartActivityEvent.finished(started, true));
    } catch (Exception e) {
      getBuckEventBus().post(StartActivityEvent.finished(started, false));
    }
  }

  /**
   * Uninstall apk from all matching devices.
   *
   * @see #installApk(SourcePathResolver, HasInstallableApk, boolean, boolean, String)
   */
  @Override
  public boolean uninstallApp(final String packageName, final boolean shouldKeepUserData)
      throws InterruptedException {
    Preconditions.checkArgument(AdbHelper.PACKAGE_NAME_PATTERN.matcher(packageName).matches());

    UninstallEvent.Started started = UninstallEvent.started(packageName);
    getBuckEventBus().post(started);
    boolean success =
        adbCall(
            "uninstall apk",
            (device) -> {
              ((RealAndroidDevice) device).uninstallApkFromDevice(packageName, shouldKeepUserData);
              return true;
            },
            false);
    getBuckEventBus().post(UninstallEvent.finished(started, success));
    return success;
  }

  public static String tryToExtractPackageNameFromManifest(
      SourcePathResolver pathResolver, ApkInfo apkInfo) {
    Path pathToManifest = pathResolver.getAbsolutePath(apkInfo.getManifestPath());
    return tryToExtractPackageNameFromManifest(pathToManifest);
  }

  static String tryToExtractPackageNameFromManifest(Path pathToManifest) {
    // Note that the file may not exist if AndroidManifest.xml is a generated file
    // and the rule has not been built yet.
    if (!Files.isRegularFile(pathToManifest)) {
      throw new HumanReadableException(
          "Manifest file %s does not exist, so could not extract package name.", pathToManifest);
    }

    try {
      return DefaultAndroidManifestReader.forPath(pathToManifest).getPackage();
    } catch (IOException e) {
      throw new HumanReadableException("Could not extract package name from %s", pathToManifest);
    }
  }

  private BuckEventBus getBuckEventBus() {
    return contextSupplier.get().getBuckEventBus();
  }

  /**
   * Returns list of devices that pass the filter. If there is an invalid combination or no devices
   * are left after filtering this function prints an error and returns null.
   */
  @Nullable
  @VisibleForTesting
  List<IDevice> filterDevices(IDevice[] allDevices) {
    if (allDevices.length == 0) {
      printError("No devices are found.");
      return null;
    }

    List<IDevice> devices = new ArrayList<>();
    Optional<Boolean> emulatorsOnly = Optional.empty();
    if (deviceOptions.isEmulatorsOnlyModeEnabled() && options.isMultiInstallModeEnabled()) {
      emulatorsOnly = Optional.empty();
    } else if (deviceOptions.isEmulatorsOnlyModeEnabled()) {
      emulatorsOnly = Optional.of(true);
    } else if (deviceOptions.isRealDevicesOnlyModeEnabled()) {
      emulatorsOnly = Optional.of(false);
    }

    int onlineDevices = 0;
    for (IDevice device : allDevices) {
      boolean passed = false;
      if (device.isOnline()) {
        onlineDevices++;

        boolean serialMatches = true;
        if (deviceOptions.getSerialNumber().isPresent()) {
          serialMatches = device.getSerialNumber().equals(deviceOptions.getSerialNumber().get());
        } else if (getEnvironment().containsKey(SERIAL_NUMBER_ENV)) {
          serialMatches = device.getSerialNumber().equals(getEnvironment().get(SERIAL_NUMBER_ENV));
        }

        boolean deviceTypeMatches;
        if (emulatorsOnly.isPresent()) {
          // Only devices of specific type are accepted:
          // either real devices only or emulators only.
          deviceTypeMatches = (emulatorsOnly.get() == createDevice(device).isEmulator());
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
      printError("No devices are found.");
      return null;
    }

    if (devices.isEmpty()) {
      printError(
          String.format(
              "Found %d connected device(s), but none of them matches specified filter.",
              onlineDevices));
      return null;
    }

    return devices;
  }

  private ImmutableMap<String, String> getEnvironment() {
    return contextSupplier.get().getEnvironment();
  }

  private RealAndroidDevice createDevice(IDevice device) {
    return new RealAndroidDevice(
        getBuckEventBus(),
        device,
        getConsole(),
        getApkFilePathFromProperties().orElse(null),
        nextAgentPort.incrementAndGet(),
        rapidInstallTypes);
  }

  private static boolean isAdbInitialized(AndroidDebugBridge adb) {
    return adb.isConnected() && adb.hasInitialDeviceList();
  }

  /**
   * Creates connection to adb and waits for this connection to be initialized and receive initial
   * list of devices.
   */
  @Nullable
  @SuppressWarnings("PMD.EmptyCatchBlock")
  private static AndroidDebugBridge createAdb(
      AndroidPlatformTarget androidPlatformTarget, ExecutionContext context)
      throws InterruptedException {
    DdmPreferences.setTimeOut(60000);

    try {
      AndroidDebugBridge.init(/* clientSupport */ false);
    } catch (IllegalStateException ex) {
      // ADB was already initialized, we're fine, so just ignore.
    }

    AndroidDebugBridge adb =
        AndroidDebugBridge.createBridge(androidPlatformTarget.getAdbExecutable().toString(), false);
    if (adb == null) {
      context
          .getConsole()
          .printBuildFailure("Failed to connect to adb. Make sure adb server is running.");
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

  private ImmutableList<AndroidDevice> getDevicesImpl() {
    if (devicesSupplierForTests.isPresent()) {
      return devicesSupplierForTests.get().get();
    }
    // Initialize adb connection.
    AndroidDebugBridge adb;
    try {
      adb =
          createAdb(
              toolchainProvider.getByName(
                  AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class),
              contextSupplier.get());
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    if (adb == null) {
      printError("Failed to create adb connection.");
      return ImmutableList.of();
    }

    // Build list of matching devices.
    List<IDevice> devices = filterDevices(adb.getDevices());
    // Found multiple devices but multi-install mode is not enabled.
    if (devices != null && devices.size() > 1 && !options.isMultiInstallModeEnabled()) {
      printError(
          String.format(
              "%d device(s) matches specified device filter (1 expected).\n"
                  + "Either disconnect other devices or enable multi-install mode (%s).",
              devices.size(), AdbOptions.MULTI_INSTALL_MODE_SHORT_ARG));
      return ImmutableList.of();
    }

    if (devices == null && restartAdbOnFailure) {
      printError("No devices found with adb, restarting adb-server.");
      adb.restart();
      devices = filterDevices(adb.getDevices());
    }
    if (devices == null) {
      return ImmutableList.of();
    }
    return devices.stream().map(this::createDevice).collect(ImmutableList.toImmutableList());
  }

  private Console getConsole() {
    return contextSupplier.get().getConsole();
  }

  private static Optional<Path> getApkFilePathFromProperties() {
    String apkFileName = System.getProperty("buck.android_agent_path");
    return Optional.ofNullable(apkFileName).map(Paths::get);
  }

  @Override
  public synchronized void close() {
    // getExecutorService() requires the context for lazy initialization, so explicitly check if it
    // has been initialized.
    if (executorService != null) {
      MostExecutors.shutdownOrThrow(
          executorService,
          10,
          TimeUnit.MINUTES,
          new InterruptionFailedException("Failed to shutdown ExecutorService."));
      executorService = null;
    }
  }

  /** An exception that indicates that an executed command returned an unsuccessful exit code. */
  public static class CommandFailedException extends IOException {
    public final String command;
    public final int exitCode;
    public final String output;

    public CommandFailedException(String command, int exitCode, String output) {
      super("Command '" + command + "' failed with code " + exitCode + ".  Output:\n" + output);
      this.command = command;
      this.exitCode = exitCode;
      this.output = output;
    }
  }

  private boolean installApkExopackage(
      SourcePathResolver pathResolver,
      HasInstallableApk hasInstallableApk,
      boolean quiet,
      @Nullable String processName)
      throws InterruptedException {
    return adbCall(
        "install exopackage apk",
        device ->
            new ExopackageInstaller(
                    pathResolver,
                    contextSupplier.get(),
                    hasInstallableApk.getProjectFilesystem(),
                    tryToExtractPackageNameFromManifest(
                        pathResolver, hasInstallableApk.getApkInfo()),
                    device)
                .doInstall(hasInstallableApk.getApkInfo(), processName),
        quiet);
  }

  private boolean installApkDirectly(
      SourcePathResolver pathResolver,
      final HasInstallableApk hasInstallableApk,
      final boolean installViaSd,
      final boolean quiet)
      throws InterruptedException {
    File apk = pathResolver.getAbsolutePath(hasInstallableApk.getApkInfo().getApkPath()).toFile();
    boolean success =
        adbCall(
            String.format("install apk %s", hasInstallableApk.getBuildTarget().toString()),
            (device) -> device.installApkOnDevice(apk, installViaSd, quiet),
            quiet);
    return success;
  }
}
