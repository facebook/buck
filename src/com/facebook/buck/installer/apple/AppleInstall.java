/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.installer.apple;

import static com.facebook.buck.apple.simulator.AppleDeviceController.AppleDeviceKindEnum.IPHONE;

import com.facebook.buck.apple.AppleInfoPlistParsing;
import com.facebook.buck.apple.device.AppleDeviceHelper;
import com.facebook.buck.apple.simulator.AppleCoreSimulatorServiceController;
import com.facebook.buck.apple.simulator.AppleDevice;
import com.facebook.buck.apple.simulator.AppleDeviceController;
import com.facebook.buck.apple.simulator.AppleSimulator;
import com.facebook.buck.apple.simulator.AppleSimulatorController;
import com.facebook.buck.apple.simulator.AppleSimulatorDiscovery;
import com.facebook.buck.installer.InstallResult;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.infer.annotation.Assertions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger; // NOPMD

/** Installs an AppleBundle */
public class AppleInstall {

  private static final String DEFAULT_APPLE_SIMULATOR_NAME = "iPhone 6s";
  private static final String DEFAULT_APPLE_TV_SIMULATOR_NAME = "Apple TV";
  private static final ImmutableList<String> APPLE_SIMULATOR_APPS =
      ImmutableList.of("Simulator.app", "iOS Simulator.app");
  private static final String IPHONE_SIMULATOR = "iphonesimulator";
  private static final String APPLE_TV_SIMULATOR = "appletvsimulator";
  private static final String IPHONE_OS = "iphoneos";
  private static final long APPLE_SIMULATOR_WAIT_MILLIS = 20_000;

  public final AppleCommandLineOptions options;
  public final Path idbPath; // Keep in CLI options
  public final AppleDeviceController appleDeviceController;
  public final DefaultProcessExecutor processExecutor;
  public final AppleInstallAppOptions appInstallOptions;
  public final Optional<Path> deviceHelperPath;
  public final Optional<Path> xcodeDeveloperPath;
  public final Logger log;
  public Optional<String> appleBundleId;

  public AppleInstall(
      DefaultProcessExecutor processExecutor,
      AppleCommandLineOptions options,
      AppleDeviceController appleDeviceController,
      AppleInstallAppOptions appInstallOptions,
      Logger log,
      Path app) {
    this.processExecutor = processExecutor;
    this.options = options;
    this.appInstallOptions = appInstallOptions;
    this.appleDeviceController = appleDeviceController;
    this.idbPath = Paths.get(options.idb_path);
    this.log = log;
    this.xcodeDeveloperPath = Optional.of(Paths.get(appInstallOptions.xcodeDeveloperPath));
    setAppleBundleID(app.getParent());
    if (appInstallOptions.deviceHelperPath != null) {
      this.deviceHelperPath = Optional.of(Paths.get(appInstallOptions.deviceHelperPath));
    } else {
      deviceHelperPath = Optional.empty();
    }
  }

  public boolean isSimulator(String platformName) {
    return platformName.equals(IPHONE_SIMULATOR) || platformName.equals(APPLE_TV_SIMULATOR);
  }

  public boolean isDevice(String platformName) {
    return platformName.equals(IPHONE_OS);
  }

  public boolean isIPhoneSimulator(String platformName) {
    return platformName.equals(IPHONE_SIMULATOR);
  }

  public boolean isAppleTVSimulator(String platformName) {
    return platformName.equals(APPLE_TV_SIMULATOR);
  }

  public InstallResult startInstall(Path app) throws IOException, InterruptedException {
    return installAppleBundle(app);
  }

  /** Chooses where and how to install the AppleBundle(Simulator or Device with IDB or simctl) */
  public InstallResult installAppleBundle(Path app) throws IOException, InterruptedException {
    if (this.isSimulator(appInstallOptions.platformName)) {
      if (appInstallOptions.useIdb) {
        return installAppleBundleForSimulatorIdb(app);
      } else {
        return installAppleBundleForSimulator(app);
      }
    }
    if (this.isDevice(appInstallOptions.platformName)) {
      if (appInstallOptions.useIdb) {
        return installAppleBundleForDeviceIbd(app);

      } else {
        return installAppleBundleForDevice(app);
      }
    }
    String errMsg =
        String.format("Install not yet supported for platform %s.", appInstallOptions.platformName);
    log.severe(errMsg);
    return new InstallResult(true, errMsg);
  }

  private Optional<AppleDevice> getSimulatorForIDB(AppleDeviceController appleDeviceController) {
    Optional<AppleDevice> simulatorByUdid = Optional.empty();
    Optional<AppleDevice> simulatorByName = Optional.empty();
    Optional<AppleDevice> bootedSimulator = Optional.empty();
    Optional<AppleDevice> defaultSimulator = Optional.empty();
    Optional<AppleDevice> arbitrarySimulator = Optional.empty();
    boolean wantUdid = options.getSerialNumber().isPresent();
    boolean wantName = options.getSimulatorName().isPresent();
    for (AppleDevice simulator : appleDeviceController.getSimulators()) {
      if (wantUdid
          && options
              .getSerialNumber()
              .get()
              .toLowerCase(Locale.US)
              .equals(simulator.getUdid().toLowerCase(Locale.US))) {
        log.info(
            String.format("Got UDID match (%s): %s", options.getSerialNumber().get(), simulator));
        simulatorByUdid = Optional.of(simulator);
        // We shouldn't need to keep looking.
        break;
      } else if (wantName
          && options
              .getSimulatorName()
              .get()
              .toLowerCase(Locale.US)
              .equals(simulator.getName().toLowerCase(Locale.US))) {
        log.info(String.format("Got name match (%s): %s", simulator.getName(), simulator));
        simulatorByName = Optional.of(simulator);
        // We assume the simulators are sorted by OS version, so we'll keep
        // looking for a more recent simulator with this name.
      } else {
        switch (appleDeviceController.getDeviceKind(simulator)) {
          case IPHONE:
          case IPAD:
            if (this.isIPhoneSimulator(appInstallOptions.platformName)) {
              if (simulator.getName().equals(DEFAULT_APPLE_SIMULATOR_NAME)) {
                log.info(
                    String.format(
                        "Got default match (%s): %s", DEFAULT_APPLE_SIMULATOR_NAME, simulator));
                defaultSimulator = Optional.of(simulator);
              }
              if (simulator.getState().equalsIgnoreCase("booted")) {
                bootedSimulator = Optional.of(simulator);
              }
              if (appleDeviceController.getDeviceKind(simulator) == IPHONE) {
                arbitrarySimulator = Optional.of(simulator);
              }
            }
            break;

          case TV:
            if (this.isAppleTVSimulator(appInstallOptions.platformName)) {
              if (simulator.getName().equals(DEFAULT_APPLE_TV_SIMULATOR_NAME)) {
                log.info(
                    String.format(
                        "Got default match (%s): %s", DEFAULT_APPLE_TV_SIMULATOR_NAME, simulator));
                defaultSimulator = Optional.of(simulator);
              }
              if (simulator.getState().equalsIgnoreCase("booted")) {
                bootedSimulator = Optional.of(simulator);
              }
              arbitrarySimulator = Optional.of(simulator);
            }
            break;

          case WATCH:
          case UNKNOWN:
            break;
        }
      }
    }

    if (wantUdid) {
      if (simulatorByUdid.isPresent()) {
        return simulatorByUdid;
      } else {
        log.info(
            String.format(
                "Asked to find simulator with UDID %s, but couldn't find one.",
                options.getSerialNumber().get()));
        return Optional.empty();
      }
    } else if (wantName) {
      if (simulatorByName.isPresent()) {
        return simulatorByName;
      } else {
        log.info(
            String.format(
                "Asked to find simulator with name %s, but couldn't find one.",
                options.getSimulatorName().get()));
        return Optional.empty();
      }
    } else if (bootedSimulator.isPresent()) {
      return bootedSimulator;
    } else if (defaultSimulator.isPresent()) {
      return defaultSimulator;
    } else {
      return arbitrarySimulator;
    }
  }

  /** Installs Apple Bundle in a Simulator using IDB */
  public InstallResult installAppleBundleForSimulatorIdb(Path appLoc)
      throws IOException, InterruptedException {
    // Choose the simulator
    Optional<AppleDevice> simulator = getSimulatorForIDB(appleDeviceController);

    if (simulator.isEmpty()) {
      return new InstallResult(
          true,
          String.format(
              "Cannot install %s (there are no simulators booted or could not find the given simulator %s or could not find the default simulator %s)",
              appInstallOptions.fullyQualifiedName,
              options.getSerialNumber().orElse("") + options.getSimulatorName().orElse(""),
              DEFAULT_APPLE_SIMULATOR_NAME));
    }

    // Boot the simulator
    if (simulator.get().getState().equalsIgnoreCase("shutdown")) {
      log.info(String.format("Starting up simulator %s", simulator.get()));
      if (!appleDeviceController.bootSimulator(simulator.get().getUdid())) {
        String errMsg =
            String.format(
                "Cannot install %s (could not start simulator %s)",
                appInstallOptions.fullyQualifiedName, simulator.get().getName());
        return new InstallResult(true, errMsg);
      }

      log.info(
          String.format(
              "Simulator started. Installing Apple bundle %s in simulator %s",
              appInstallOptions.fullyQualifiedName, simulator.get()));
    } else {
      log.info(String.format("Simulator %s already running", simulator.get()));
    }

    // Install the bundle
    if (appleDeviceController.installBundle(simulator.get().getUdid(), appLoc).isEmpty()) {
      String errMsg =
          String.format(
              "Cannot install %s (could not install bundle %s in simulator %s)",
              appInstallOptions.fullyQualifiedName, appLoc, simulator.get().getName());
      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }

    // Brings the simulator to the front
    appleDeviceController.bringSimulatorToFront(simulator.get().getUdid());
    boolean run = options.getRun();

    // Launching the bundle
    if (run) {
      if (appleBundleId.isEmpty()) {
        String errMsg =
            String.format(
                "Cannot install %s (could not get bundle ID from %s)",
                appInstallOptions.fullyQualifiedName, appInstallOptions.infoPlistpath);
        log.severe(errMsg);
        return new InstallResult(true, errMsg);
      }
      boolean waitForDebugger = options.getWaitForDebugger();

      // Launching
      Optional<String> debugCommand = Optional.empty();
      if (waitForDebugger) {
        debugCommand =
            appleDeviceController.startDebugServer(simulator.get().getUdid(), appleBundleId.get());
        if (debugCommand.isEmpty()) {
          String errMsg = "Could not start the debugserver";
          log.severe(errMsg);
          return new InstallResult(true, errMsg);
        }
      } else {
        if (!appleDeviceController.launchInstalledBundle(
            simulator.get().getUdid(), appleBundleId.get())) {
          String errMsg =
              String.format(
                  "Cannot launch %s (failed to launch bundle ID %s)",
                  appInstallOptions.fullyQualifiedName, appleBundleId.get());
          log.severe(errMsg);
          return new InstallResult(true, errMsg);
        }
      }
      String debugOptionMessage =
          waitForDebugger
              ? "(waiting for debugger) Run lldb and then "
                  + debugCommand.get()
                  + " to run the debugger."
              : "";
      log.info(
          String.format(
              "Successfully launched %s. %s",
              appInstallOptions.fullyQualifiedName, debugOptionMessage));
    } else {
      log.info(
          String.format(
              "Successfully installed %s. (Use `buck install -r` to run.)",
              appInstallOptions.fullyQualifiedName));
    }
    return new InstallResult(false, "");
  }

  private InstallResult installAppleBundleForSimulator(Path appPath)
      throws IOException, InterruptedException {

    if (xcodeDeveloperPath.isEmpty()) {
      String errMsg =
          String.format(
              "Cannot install %s (Xcode not found)", appInstallOptions.fullyQualifiedName);
      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }

    Path simctlPath = xcodeDeveloperPath.get().resolve("usr/bin/simctl");
    Optional<AppleSimulator> appleSimulator =
        getAppleSimulatorForBundle(processExecutor, simctlPath);

    if (appleSimulator.isEmpty()) {
      String errMsg =
          String.format(
              "Cannot install %s (no appropriate simulator found, try specifying a simulator by name)",
              appInstallOptions.fullyQualifiedName);
      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }

    Path iosSimulatorPath = null;
    Path xcodeApplicationsPath = xcodeDeveloperPath.get().resolve("Applications");
    for (String simulatorApp : APPLE_SIMULATOR_APPS) {
      Path resolvedSimulatorPath = xcodeApplicationsPath.resolve(simulatorApp);
      if (Files.isDirectory(resolvedSimulatorPath)) {
        iosSimulatorPath = resolvedSimulatorPath;
        break;
      }
    }

    if (iosSimulatorPath == null) {
      String errMsg =
          String.format(
              "Cannot install %s (could not find simulator under %s, checked %s)",
              appInstallOptions.fullyQualifiedName, xcodeApplicationsPath, APPLE_SIMULATOR_APPS);
      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }

    AppleSimulatorController appleSimulatorController =
        new AppleSimulatorController(processExecutor, simctlPath, iosSimulatorPath);

    boolean shouldWaitForSimulatorsToShutdown = false;
    if (!appleSimulatorController.canStartSimulator(appleSimulator.get().getUdid())) {
      log.warning("Cannot start simulator, killing simulators and trying again.");
      AppleCoreSimulatorServiceController appleCoreSimulatorServiceController =
          new AppleCoreSimulatorServiceController(processExecutor);
      if (!appleCoreSimulatorServiceController.killSimulatorProcesses()) {
        String errMsg = "Could not kill running simulator processes.";
        log.severe(errMsg);
        return new InstallResult(true, errMsg);
      }
      shouldWaitForSimulatorsToShutdown = true;

      // Killing the simulator can cause the UDIDs to change, so we need to fetch them again.
      appleSimulator = getAppleSimulatorForBundle(processExecutor, simctlPath);
      if (appleSimulator.isEmpty()) {
        String errMsg =
            String.format(
                "Cannot install %s (no appropriate simulator found, try specifying a simulator by name)",
                appInstallOptions.fullyQualifiedName);
        log.severe(errMsg);
        return new InstallResult(true, errMsg);
      }
    }
    long remainingMillis = APPLE_SIMULATOR_WAIT_MILLIS;
    if (shouldWaitForSimulatorsToShutdown) {
      Optional<Long> shutdownMillis =
          appleSimulatorController.waitForSimulatorsToShutdown(remainingMillis);
      if (shutdownMillis.isEmpty()) {
        String errMsg =
            String.format(
                "Cannot install %s (simulators did not shut down within %d ms).",
                appInstallOptions.fullyQualifiedName, APPLE_SIMULATOR_WAIT_MILLIS);
        log.severe(errMsg);
        return new InstallResult(true, errMsg);
      }
      log.info(String.format("Simulators shut down in %d millis.", shutdownMillis.get()));
      remainingMillis -= shutdownMillis.get();
    }
    log.info(String.format("Starting up simulator %s", appleSimulator.get()));
    Optional<Long> startMillis =
        appleSimulatorController.startSimulator(appleSimulator.get().getUdid(), remainingMillis);

    if (startMillis.isEmpty()) {
      String errMsg =
          String.format(
              "Cannot install %s (could not start simulator %s within %d ms)",
              appInstallOptions.fullyQualifiedName,
              appleSimulator.get().getName(),
              APPLE_SIMULATOR_WAIT_MILLIS);
      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }
    log.info(
        String.format(
            "Simulator started in %d ms. Installing Apple bundle %s in simulator %s",
            startMillis.get(), appInstallOptions.fullyQualifiedName, appleSimulator.get()));

    if (!appleSimulatorController.installBundleInSimulator(
        appleSimulator.get().getUdid(), appPath)) {
      String errMsg =
          String.format(
              "Cannot install %s (could not install bundle %s in simulator %s)",
              appInstallOptions.fullyQualifiedName, appPath, appleSimulator.get().getName());

      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }
    boolean run = options.getRun();

    if (run) {
      return launchAppleBundle(appleSimulatorController, appleSimulator.get());

    } else {
      log.info(String.format("Successfully installed %s", appInstallOptions.fullyQualifiedName));
      return new InstallResult(false, "");
    }
  }

  private Optional<AppleSimulator> getAppleSimulatorForBundle(
      ProcessExecutor processExecutor, Path simctlPath) throws IOException, InterruptedException {

    Optional<AppleSimulator> simulatorByUdid = Optional.empty();
    Optional<AppleSimulator> simulatorByName = Optional.empty();
    Optional<AppleSimulator> defaultSimulator = Optional.empty();
    Optional<AppleSimulator> arbitrarySimulator = Optional.empty();

    boolean wantUdid = options.getSerialNumber().isPresent();
    boolean wantName = options.getSimulatorName().isPresent();

    for (AppleSimulator simulator :
        AppleSimulatorDiscovery.discoverAppleSimulators(processExecutor, simctlPath)) {
      if (wantUdid
          && options
              .getSerialNumber()
              .get()
              .toLowerCase(Locale.US)
              .equals(simulator.getUdid().toLowerCase(Locale.US))) {

        log.info(
            String.format("Got UDID match (%s): %s", options.getSerialNumber().get(), simulator));
        simulatorByUdid = Optional.of(simulator);
        // We shouldn't need to keep looking.
        break;
      } else if (wantName
          && options
              .getSimulatorName()
              .get()
              .toLowerCase(Locale.US)
              .equals(simulator.getName().toLowerCase(Locale.US))) {
        log.info(String.format("Got name match (%s): %s", simulator.getName(), simulator));
        simulatorByName = Optional.of(simulator);
        // We assume the simulators are sorted by OS version, so we'll keep
        // looking for a more recent simulator with this name.
      } else if (this.isIPhoneSimulator(appInstallOptions.platformName)) {
        if (simulator.getName().equals(DEFAULT_APPLE_SIMULATOR_NAME)) {
          log.info(
              String.format("Got default match (%s): %s", DEFAULT_APPLE_SIMULATOR_NAME, simulator));
          defaultSimulator = Optional.of(simulator);
        }
        if (simulator.getName().startsWith("iPhone")) {
          arbitrarySimulator = Optional.of(simulator);
        }
      } else if (this.isAppleTVSimulator(appInstallOptions.platformName)) {
        if (simulator.getName().equals(DEFAULT_APPLE_TV_SIMULATOR_NAME)) {
          log.info(
              String.format(
                  "Got default match (%s): %s", DEFAULT_APPLE_TV_SIMULATOR_NAME, simulator));
          defaultSimulator = Optional.of(simulator);
        }
        if (simulator.getName().startsWith("Apple TV")) {
          arbitrarySimulator = Optional.of(simulator);
        }
      }
    }

    if (wantUdid) {
      if (simulatorByUdid.isPresent()) {
        return simulatorByUdid;
      } else {
        log.info(
            String.format(
                "Asked to find simulator with UDID %s, but couldn't find one.",
                options.getSerialNumber().get()));
        return Optional.empty();
      }
    } else if (wantName) {
      if (simulatorByName.isPresent()) {
        return simulatorByName;
      } else {
        log.info(
            String.format(
                "Asked to find simulator with name %s, but couldn't find one.",
                options.getSimulatorName().get()));
        return Optional.empty();
      }
    } else if (defaultSimulator.isPresent()) {
      return defaultSimulator;
    } else {
      return arbitrarySimulator;
    }
  }

  private InstallResult launchAppleBundle(
      AppleSimulatorController appleSimulatorController, AppleSimulator appleSimulator)
      throws IOException, InterruptedException {

    log.info(
        String.format(
            "Launching Apple bundle %s in simulator %s",
            appInstallOptions.fullyQualifiedName, appleSimulator));

    if (appleBundleId.isEmpty()) {
      log.info(String.format("Cannot install %s c", appInstallOptions.fullyQualifiedName));
    }
    List<String> runArgs = new ArrayList<>();
    boolean waitForDebugger = false;

    Optional<Long> launchedPid =
        appleSimulatorController.launchInstalledBundleInSimulator(
            appleSimulator.getUdid(),
            appleBundleId.get(),
            waitForDebugger
                ? AppleSimulatorController.LaunchBehavior.WAIT_FOR_DEBUGGER
                : AppleSimulatorController.LaunchBehavior.DO_NOT_WAIT_FOR_DEBUGGER,
            runArgs);
    if (launchedPid.isEmpty()) {
      String errMsg =
          String.format(
              "Cannot launch %s (failed to launch bundle ID %s)",
              appInstallOptions.fullyQualifiedName, appleBundleId.get());
      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }

    log.info(
        String.format(
            "Successfully launched %s%s. To debug, run: lldb -p %d",
            appleBundleId, waitForDebugger ? " (waiting for debugger)" : "", launchedPid.get()));
    return new InstallResult(false, "");
  }

  private InstallResult installAppleBundleForDeviceIbd(Path appLoc)
      throws IOException, InterruptedException {

    // Choose the physical device
    ImmutableSet<AppleDevice> physicalDevices = appleDeviceController.getPhysicalDevices();
    if (physicalDevices.isEmpty()) {
      String errMsg = "Could not find any physical devices connected";
      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }

    AppleDevice chosenDevice = null;
    if (options.getTargetDeviceOptions().getSerialNumber().isPresent()) {
      String udidPrefix =
          Assertions.assertNotNull(options.getTargetDeviceOptions().getSerialNumber().get())
              .toLowerCase();
      for (AppleDevice physicalDevice : physicalDevices) {
        if (physicalDevice.getUdid().startsWith(udidPrefix)) {
          chosenDevice = physicalDevice;
          break;
        }
      }
      if (chosenDevice == null) {
        String errMsg =
            String.format(
                "Cannot install %s to the device %s (no connected devices with that UDID/prefix)",
                appInstallOptions.fullyQualifiedName, udidPrefix);
        log.severe(errMsg);
        return new InstallResult(true, errMsg);
      }
    } else {
      if (physicalDevices.size() > 1) {
        log.info(
            "More than one connected device found, and no device ID specified.  A device will be"
                + " arbitrarily picked.");
      }
      chosenDevice = physicalDevices.iterator().next();
    }

    // Install the bundle
    if (appleDeviceController.installBundle(chosenDevice.getUdid(), appLoc).isEmpty()) {
      String errMsg =
          String.format(
              "Cannot install %s (could not install bundle %s in physical device %s)",
              appInstallOptions.fullyQualifiedName, appLoc, chosenDevice.getName());
      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }
    boolean run = options.getRun();
    boolean waitForDebugger = options.getWaitForDebugger();
    List<String> args = options.getRunArgs();

    // Launching the bundle
    if (run) {
      // Get the bundleID
      if (appleBundleId.isEmpty()) {
        String errMsg =
            String.format(
                "Cannot install %s (could not get bundle ID from %s)",
                appInstallOptions.fullyQualifiedName, appInstallOptions.infoPlistpath);
        log.severe(errMsg);
        return new InstallResult(true, errMsg);
      }

      // Launching
      if (!appleDeviceController.launchInstalledBundle(
          chosenDevice.getUdid(), appleBundleId.get())) {
        String errMsg =
            String.format(
                "Cannot launch %s (failed to launch bundle ID %s)",
                appInstallOptions.fullyQualifiedName, appleBundleId.get());
        log.severe(errMsg);
        return new InstallResult(true, errMsg);
      }
      log.info(
          String.format(
              "Successfully launched %s%s. To debug, run: lldb -p",
              args, waitForDebugger ? " (waiting for debugger)" : ""));
    } else {
      log.info(
          String.format(
              "Successfully installed %s. (Use `buck install -r %s` to run and -w to have the app waiting for debugger.)",
              args, args));
    }
    return new InstallResult(false, "");
  }

  private InstallResult installAppleBundleForDevice(Path appLoc) {
    // if deviceHelperTarget, it should be built in buck2 and passed as deviceHelperPath
    if (deviceHelperPath.isEmpty()) {
      String errMsg =
          String.format(
              "Cannot install %s (could not find path to device install helper tool)",
              appInstallOptions.fullyQualifiedName);
      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }

    AppleDeviceHelper helper = new AppleDeviceHelper(processExecutor, deviceHelperPath.get());
    ImmutableMap<String, String> connectedDevices = helper.getConnectedDevices();
    if (connectedDevices.isEmpty()) {
      String errMsg =
          String.format(
              "Cannot install %s (no connected devices found)",
              appInstallOptions.fullyQualifiedName);
      log.severe(errMsg);
      return new InstallResult(true, errMsg);
    }

    String selectedUdid = null;
    if (options.getTargetDeviceOptions().getSerialNumber().isPresent()) {
      String udidPrefix =
          Assertions.assertNotNull(options.getTargetDeviceOptions().getSerialNumber().get())
              .toLowerCase();
      for (String udid : connectedDevices.keySet()) {
        if (udid.startsWith(udidPrefix)) {
          selectedUdid = udid;
          break;
        }
      }
      if (selectedUdid == null) {
        String errMsg =
            String.format(
                "Cannot install %s to the device %s (no connected devices with that UDID/prefix)",
                appInstallOptions.fullyQualifiedName, udidPrefix);
        log.severe(errMsg);
        return new InstallResult(true, errMsg);
      }
    } else {
      if (connectedDevices.size() > 1) {
        log.info(
            "More than one connected device found, and no device ID specified.  A device will be"
                + " arbitrarily picked.");
      }

      selectedUdid = connectedDevices.keySet().iterator().next();
    }
    log.info(
        String.format(
            "Installing %s to device %s (%s)",
            appInstallOptions.fullyQualifiedName,
            selectedUdid,
            connectedDevices.get(selectedUdid)));
    if (helper.installBundleOnDevice(selectedUdid, appLoc)) {
      log.info(
          String.format(
              "Installing %s to device %s (%s)",
              appInstallOptions.fullyQualifiedName,
              selectedUdid,
              connectedDevices.get(selectedUdid)));
      boolean run = options.getRun();
      if (run) {
        if (appleBundleId.isEmpty()) {
          String errMsg =
              String.format(
                  "Cannot run %s (could not get bundle ID from %s)",
                  appInstallOptions.fullyQualifiedName, appInstallOptions.infoPlistpath);
          log.severe(errMsg);
          return new InstallResult(true, errMsg);
        }
        if (helper.runBundleOnDevice(selectedUdid, appleBundleId.get())) {
          log.info("Successfully running");
          return new InstallResult(false, "");
        } else {
          String errMsg =
              String.format(
                  "Failed to run %s on device %s (%s)",
                  appInstallOptions.fullyQualifiedName,
                  selectedUdid,
                  connectedDevices.get(selectedUdid));
          log.severe(errMsg);
          return new InstallResult(true, errMsg);
        }
      } else {
        log.info("Successfully installed");
        return new InstallResult(false, "");
      }
    } else {
      String errMsg =
          String.format(
              "Failed to install %s on device %s (%s)",
              appInstallOptions.fullyQualifiedName,
              selectedUdid,
              connectedDevices.get(selectedUdid));
      log.severe(errMsg);

      return new InstallResult(true, errMsg);
    }
  }

  private void setAppleBundleID(Path parent) {
    this.appleBundleId = Optional.empty();
    Path plistPath = parent.resolve(appInstallOptions.infoPlistpath.getFileName());
    try (InputStream bundlePlistStream = Files.newInputStream(plistPath)) {
      log.info(String.format("Getting Apple Bundle ID from %s", plistPath));
      this.appleBundleId =
          AppleInfoPlistParsing.getBundleIdFromPlistStream(plistPath, bundlePlistStream);
    } catch (IOException e) {
      log.warning(String.format("Could not get apple bundle ID from %s", plistPath));
    }
  }
}
