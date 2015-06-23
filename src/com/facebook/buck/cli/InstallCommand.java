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

import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleInfoPlistParsing;
import com.facebook.buck.apple.simulator.AppleCoreSimulatorServiceController;
import com.facebook.buck.apple.simulator.AppleSimulator;
import com.facebook.buck.apple.simulator.AppleSimulatorController;
import com.facebook.buck.apple.simulator.AppleSimulatorDiscovery;
import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.android.AdbOptions;
import com.facebook.buck.android.TargetDeviceOptions;
import com.facebook.buck.cli.UninstallCommand.UninstallOptions;
import com.facebook.buck.command.Build;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ExopackageInfo;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.UnixUserIdFetcher;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.InputStream;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Command so a user can build and install an APK.
 */
public class InstallCommand extends BuildCommand {

  private static final Logger LOG = Logger.get(InstallCommand.class);
  private static final long APPLE_SIMULATOR_WAIT_MILLIS = 20000;

  @VisibleForTesting static final String RUN_LONG_ARG = "--run";
  @VisibleForTesting static final String RUN_SHORT_ARG = "-r";
  @VisibleForTesting static final String WAIT_FOR_DEBUGGER_LONG_ARG = "--wait-for-debugger";
  @VisibleForTesting static final String WAIT_FOR_DEBUGGER_SHORT_ARG = "-w";
  @VisibleForTesting static final String INSTALL_VIA_SD_LONG_ARG = "--via-sd";
  @VisibleForTesting static final String INSTALL_VIA_SD_SHORT_ARG = "-S";
  @VisibleForTesting static final String ACTIVITY_LONG_ARG = "--activity";
  @VisibleForTesting static final String ACTIVITY_SHORT_ARG = "-a";
  @VisibleForTesting static final String UNINSTALL_LONG_ARG = "--uninstall";
  @VisibleForTesting static final String UNINSTALL_SHORT_ARG = "-u";

  @Option(
      name = UNINSTALL_LONG_ARG,
      aliases = { UNINSTALL_SHORT_ARG },
      usage = "Uninstall the existing version before installing.")
  private boolean uninstallFirst = false;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private UninstallOptions uninstallOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private AdbCommandLineOptions adbOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private TargetDeviceCommandLineOptions deviceOptions;

  @Option(
      name = "--",
      usage = "Arguments passed when running with -r. Only valid for Apple targets.",
      handler = ConsumeAllOptionsHandler.class,
      depends = "-r")
  private List<String> runArgs = Lists.newArrayList();

  @Option(
      name = RUN_LONG_ARG,
      aliases = { RUN_SHORT_ARG },
      usage = "Run an activity (the default activity for package unless -a is specified).")
  private boolean run = false;

  @Option(
      name = WAIT_FOR_DEBUGGER_LONG_ARG,
      aliases = { WAIT_FOR_DEBUGGER_SHORT_ARG },
      usage = "Have the launched process wait for the debugger")
  private boolean waitForDebugger = false;

  @Option(
      name = INSTALL_VIA_SD_LONG_ARG,
      aliases = { INSTALL_VIA_SD_SHORT_ARG },
      usage = "Copy package to external storage (SD) instead of /data/local/tmp before installing.")
  private boolean installViaSd = false;

  @Option(
      name = ACTIVITY_LONG_ARG,
      aliases = { ACTIVITY_SHORT_ARG },
      metaVar = "<pkg/activity>",
      usage = "Activity to launch e.g. com.facebook.katana/.LoginActivity. Implies -r.")
  @Nullable
  private String activity = null;

  public AdbOptions adbOptions() {
    return adbOptions.getAdbOptions();
  }

  public TargetDeviceOptions targetDeviceOptions() {
    return deviceOptions.getTargetDeviceOptions();
  }

  public UninstallOptions uninstallOptions() {
    return uninstallOptions;
  }

  public boolean shouldUninstallFirst() {
    return uninstallFirst;
  }

  public boolean shouldStartActivity() {
    return (activity != null) || run;
  }

  public boolean shouldInstallViaSd() {
    return installViaSd;
  }

  @Nullable
  public String getActivityToStart() {
    return activity;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    // Make sure that only one build target is specified.
    if (getArguments().size() != 1) {
      params.getConsole().getStdErr().println(
          "Must specify exactly one rule.");
      return 1;
    }

    // Build the specified target.
    int exitCode = super.runWithoutHelp(params);
    if (exitCode != 0) {
      return exitCode;
    }

    Build build = super.getBuild();
    ActionGraph graph = build.getActionGraph();
    BuildRule buildRule = Preconditions.checkNotNull(
        graph.findBuildRuleByTarget(getBuildTargets().get(0)));

    if (buildRule instanceof InstallableApk) {
      return installApk(
          params,
          (InstallableApk) buildRule,
          build.getExecutionContext());
    } else if (buildRule instanceof AppleBundle) {
      AppleBundle appleBundle = (AppleBundle) buildRule;
      params.getBuckEventBus().post(InstallEvent.started(appleBundle.getBuildTarget()));
      exitCode = installAppleBundle(
          params,
          appleBundle,
          build.getExecutionContext().getProjectFilesystem(),
          build.getExecutionContext().getProcessExecutor());
      params.getBuckEventBus().post(
          InstallEvent.finished(appleBundle.getBuildTarget(), exitCode == 0));
      return exitCode;
    } else {
      params.getConsole().printBuildFailure(
          String.format(
              "Specified rule %s must be of type android_binary() or apk_genrule() or " +
                  "apple_bundle() but was %s().\n",
              buildRule.getFullyQualifiedName(),
              buildRule.getType()));
      return 1;
    }
  }

  private int installApk(
      CommandRunnerParams params,
      InstallableApk installableApk,
      ExecutionContext executionContext) throws IOException, InterruptedException {
    final AdbHelper adbHelper = new AdbHelper(
        adbOptions(),
        targetDeviceOptions(),
        executionContext,
        params.getConsole(),
        params.getBuckEventBus(),
        params.getBuckConfig().getRestartAdbOnFailure());

    // Uninstall the app first, if requested.
    if (shouldUninstallFirst()) {
      String packageName = AdbHelper.tryToExtractPackageNameFromManifest(
          installableApk,
          executionContext);
      adbHelper.uninstallApp(packageName, uninstallOptions().shouldKeepUserData());
      // Perhaps the app wasn't installed to begin with, shouldn't stop us.
    }

    boolean installSuccess;
    Optional<ExopackageInfo> exopackageInfo = installableApk.getExopackageInfo();
    if (exopackageInfo.isPresent()) {
      installSuccess = new ExopackageInstaller(
          executionContext,
          adbHelper,
          installableApk)
          .install();
    } else {
      installSuccess = adbHelper.installApk(installableApk, shouldInstallViaSd());
    }
    if (!installSuccess) {
      return 1;
    }

    // We've installed the application successfully.
    // Is either of --activity or --run present?
    if (shouldStartActivity()) {
      int exitCode = adbHelper.startActivity(installableApk, getActivityToStart());
      if (exitCode != 0) {
        return exitCode;
      }
    }

    return 0;
  }

  private int installAppleBundle(
      CommandRunnerParams params,
      AppleBundle appleBundle,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor) throws IOException, InterruptedException {

    // TODO(user): This should be shared with the build and passed down.
    AppleConfig appleConfig = new AppleConfig(params.getBuckConfig());
    Optional<Path> xcodeDeveloperPath = appleConfig.getAppleDeveloperDirectorySupplier(
        processExecutor).get();
    if (!xcodeDeveloperPath.isPresent()) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (Xcode not found)", appleBundle.getFullyQualifiedName()));
      return 1;
    }

    UnixUserIdFetcher userIdFetcher = new UnixUserIdFetcher();
    AppleCoreSimulatorServiceController appleCoreSimulatorServiceController =
        new AppleCoreSimulatorServiceController(processExecutor);

    Optional<Path> coreSimulatorServicePath =
        appleCoreSimulatorServiceController.getCoreSimulatorServicePath(userIdFetcher);

    boolean shouldWaitForSimulatorsToShutdown = false;

    if (!coreSimulatorServicePath.isPresent() ||
        !coreSimulatorServicePath.get().toRealPath().startsWith(
            xcodeDeveloperPath.get().toRealPath())) {
      LOG.warn(
          "Core simulator service path %s does not match developer directory %s, " +
          "killing all simulators.",
          coreSimulatorServicePath,
          xcodeDeveloperPath.get());
      if (!appleCoreSimulatorServiceController.killSimulatorProcesses()) {
        params.getConsole().printBuildFailure("Could not kill running simulator processes.");
        return 1;
      }

      shouldWaitForSimulatorsToShutdown = true;
    }

    Path simctlPath = xcodeDeveloperPath.get().resolve("usr/bin/simctl");
    Optional<AppleSimulator> appleSimulator = getAppleSimulatorForBundle(
        appleBundle,
        processExecutor,
        simctlPath);

    if (!appleSimulator.isPresent()) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (no appropriate simulator found)",
              appleBundle.getFullyQualifiedName()));
      return 1;
    }

    Path iosSimulatorPath = xcodeDeveloperPath.get().resolve("Applications/iOS Simulator.app");
    AppleSimulatorController appleSimulatorController = new AppleSimulatorController(
        processExecutor,
        simctlPath,
        iosSimulatorPath);

    if (!appleSimulatorController.canStartSimulator(appleSimulator.get().getUdid())) {
      LOG.warn("Cannot start simulator %s, killing simulators and trying again.");
      if (!appleCoreSimulatorServiceController.killSimulatorProcesses()) {
        params.getConsole().printBuildFailure("Could not kill running simulator processes.");
        return 1;
      }

      shouldWaitForSimulatorsToShutdown = true;

      // Killing the simulator can cause the UDIDs to change, so we need to fetch them again.
      appleSimulator = getAppleSimulatorForBundle(appleBundle, processExecutor, simctlPath);
      if (!appleSimulator.isPresent()) {
        params.getConsole().printBuildFailure(
            String.format(
                "Cannot install %s (no appropriate simulator found)",
                appleBundle.getFullyQualifiedName()));
        return 1;
      }
    }

    long remainingMillis = APPLE_SIMULATOR_WAIT_MILLIS;
    if (shouldWaitForSimulatorsToShutdown) {
      Optional<Long> shutdownMillis = appleSimulatorController.waitForSimulatorsToShutdown(
          remainingMillis);
      if (!shutdownMillis.isPresent()) {
        params.getConsole().printBuildFailure(
            String.format(
                "Cannot install %s (simulators did not shut down within %d ms).",
                appleBundle.getFullyQualifiedName(),
                APPLE_SIMULATOR_WAIT_MILLIS));
        return 1;
      }

      LOG.debug("Simulators shut down in %d millis.", shutdownMillis.get());
      remainingMillis -= shutdownMillis.get();
    }

    LOG.debug("Starting up simulator %s", appleSimulator.get());

    Optional<Long> startMillis = appleSimulatorController.startSimulator(
        appleSimulator.get().getUdid(),
        remainingMillis);

    if (!startMillis.isPresent()) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (could not start simulator %s within %d ms)",
              appleBundle.getFullyQualifiedName(),
              appleSimulator.get().getName(),
              APPLE_SIMULATOR_WAIT_MILLIS));
      return 1;
    }

    LOG.debug(
        "Simulator started in %d ms. Installing Apple bundle %s in simulator %s",
        startMillis.get(),
        appleBundle,
        appleSimulator.get());

    if (!appleSimulatorController.installBundleInSimulator(
            appleSimulator.get().getUdid(),
            projectFilesystem.resolve(Preconditions.checkNotNull(appleBundle.getPathToOutput())))) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (could not install bundle %s in simulator %s)",
              appleBundle.getFullyQualifiedName(),
              appleBundle.getPathToOutput(),
              appleSimulator.get().getName()));
      return 1;
    }

    if (run) {
      return launchAppleBundle(
          params,
          appleBundle,
          appleSimulatorController,
          projectFilesystem,
          appleSimulator.get());
    } else {
      params.getConsole().printSuccess(
          String.format(
              "Successfully installed %s. (Use `buck install -r %s` to run.)",
              getArguments().get(0),
              getArguments().get(0)));
      return 0;
    }
  }

  private int launchAppleBundle(
      CommandRunnerParams params,
      AppleBundle appleBundle,
      AppleSimulatorController appleSimulatorController,
      ProjectFilesystem projectFilesystem,
      AppleSimulator appleSimulator) throws IOException, InterruptedException {

    LOG.debug("Launching Apple bundle %s in simulator %s", appleBundle, appleSimulator);

    Optional<String> appleBundleId;
    try (InputStream bundlePlistStream =
             projectFilesystem.getInputStreamForRelativePath(appleBundle.getInfoPlistPath())){
        appleBundleId = AppleInfoPlistParsing.getBundleIdFromPlistStream(bundlePlistStream);
    }
    if (!appleBundleId.isPresent()) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot install %s (could not get bundle ID from %s)",
              appleBundle.getFullyQualifiedName(),
              appleBundle.getInfoPlistPath()));
      return 1;
    }

    Optional<Long> launchedPid = appleSimulatorController.launchInstalledBundleInSimulator(
        appleSimulator.getUdid(),
        appleBundleId.get(),
        waitForDebugger ? AppleSimulatorController.LaunchBehavior.WAIT_FOR_DEBUGGER :
            AppleSimulatorController.LaunchBehavior.DO_NOT_WAIT_FOR_DEBUGGER,
        runArgs);
    if (!launchedPid.isPresent()) {
      params.getConsole().printBuildFailure(
          String.format(
              "Cannot launch %s (failed to launch bundle ID %s)",
              appleBundle.getFullyQualifiedName(),
              appleBundleId.get()));
      return 1;
    }

    params.getConsole().printSuccess(
        String.format(
            "Successfully launched %s%s. To debug, run: lldb -p %d",
            getArguments().get(0),
            waitForDebugger ? " (waiting for debugger)" : "",
            launchedPid.get()));

    return 0;
  }

  private Optional<AppleSimulator> getAppleSimulatorForBundle(
      AppleBundle appleBundle,
      ProcessExecutor processExecutor,
      Path simctlPath) throws IOException, InterruptedException {
    LOG.debug("Choosing simulator for %s", appleBundle);

    for (AppleSimulator simulator : AppleSimulatorDiscovery.discoverAppleSimulators(
             processExecutor,
             simctlPath)) {
      // TODO(user): Choose this from the flavor and add more command-line params to
      // switch between iPhone/iPad simulator.
      if (simulator.getName().equals("iPhone 5s")) {
        return Optional.of(simulator);
      }
    }

    return Optional.<AppleSimulator>absent();
  }

  @Override
  public String getShortDescription() {
    return "builds and installs an application";
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

}
