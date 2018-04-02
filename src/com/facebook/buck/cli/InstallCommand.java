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

import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.android.AndroidBinary;
import com.facebook.buck.android.AndroidInstallConfig;
import com.facebook.buck.android.HasInstallableApk;
import com.facebook.buck.android.exopackage.AndroidDevicesHelper;
import com.facebook.buck.android.exopackage.AndroidDevicesHelperFactory;
import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleInfoPlistParsing;
import com.facebook.buck.apple.device.AppleDeviceHelper;
import com.facebook.buck.apple.simulator.AppleCoreSimulatorServiceController;
import com.facebook.buck.apple.simulator.AppleSimulator;
import com.facebook.buck.apple.simulator.AppleSimulatorController;
import com.facebook.buck.apple.simulator.AppleSimulatorDiscovery;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.cli.UninstallCommand.UninstallOptions;
import com.facebook.buck.command.Build;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasInstallHelpers;
import com.facebook.buck.rules.InstallTrigger;
import com.facebook.buck.rules.NoopInstallable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.UnixUserIdFetcher;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.infer.annotation.Assertions;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Closer;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

/** Command so a user can build and install an APK. */
public class InstallCommand extends BuildCommand {

  private static final Logger LOG = Logger.get(InstallCommand.class);
  private static final long APPLE_SIMULATOR_WAIT_MILLIS = 20000;
  private static final ImmutableList<String> APPLE_SIMULATOR_APPS =
      ImmutableList.of("Simulator.app", "iOS Simulator.app");
  private static final String DEFAULT_APPLE_SIMULATOR_NAME = "iPhone 5s";
  private static final String DEFAULT_APPLE_TV_SIMULATOR_NAME = "Apple TV";
  private static final InstallResult FAILURE =
      InstallResult.builder().setExitCode(ExitCode.RUN_ERROR).build();

  @VisibleForTesting static final String RUN_LONG_ARG = "--run";
  @VisibleForTesting static final String RUN_SHORT_ARG = "-r";
  @VisibleForTesting static final String WAIT_FOR_DEBUGGER_LONG_ARG = "--wait-for-debugger";
  @VisibleForTesting static final String WAIT_FOR_DEBUGGER_SHORT_ARG = "-w";
  @VisibleForTesting static final String INSTALL_VIA_SD_LONG_ARG = "--via-sd";
  @VisibleForTesting static final String INSTALL_VIA_SD_SHORT_ARG = "-S";
  @VisibleForTesting static final String ACTIVITY_LONG_ARG = "--activity";
  @VisibleForTesting static final String ACTIVITY_SHORT_ARG = "-a";
  @VisibleForTesting static final String PROCESS_LONG_ARG = "--process";
  @VisibleForTesting static final String PROCESS_SHORT_ARG = "-p";
  @VisibleForTesting static final String UNINSTALL_LONG_ARG = "--uninstall";
  @VisibleForTesting static final String UNINSTALL_SHORT_ARG = "-u";

  @Option(
    name = UNINSTALL_LONG_ARG,
    aliases = {UNINSTALL_SHORT_ARG},
    usage = "Uninstall the existing version before installing."
  )
  private boolean uninstallFirst = false;

  @AdditionalOptions @SuppressFieldNotInitialized private UninstallOptions uninstallOptions;

  @AdditionalOptions @SuppressFieldNotInitialized private AdbCommandLineOptions adbOptions;

  @AdditionalOptions @SuppressFieldNotInitialized
  private TargetDeviceCommandLineOptions deviceOptions;

  @Option(
    name = "--",
    usage = "Arguments passed when running with -r. Only valid for Apple targets.",
    handler = ConsumeAllOptionsHandler.class,
    depends = "-r"
  )
  private List<String> runArgs = new ArrayList<>();

  @Option(
    name = RUN_LONG_ARG,
    aliases = {RUN_SHORT_ARG},
    usage = "Run an activity (the default activity for package unless -a is specified)."
  )
  private boolean run = false;

  @Option(
    name = WAIT_FOR_DEBUGGER_LONG_ARG,
    aliases = {WAIT_FOR_DEBUGGER_SHORT_ARG},
    usage = "Have the launched process wait for the debugger"
  )
  private boolean waitForDebugger = false;

  @Option(
    name = INSTALL_VIA_SD_LONG_ARG,
    aliases = {INSTALL_VIA_SD_SHORT_ARG},
    usage = "Copy package to external storage (SD) instead of /data/local/tmp before installing."
  )
  private boolean installViaSd = false;

  @Option(
    name = ACTIVITY_LONG_ARG,
    aliases = {ACTIVITY_SHORT_ARG},
    metaVar = "<pkg/activity>",
    usage = "Activity to launch e.g. com.facebook.katana/.LoginActivity. Implies -r."
  )
  @Nullable
  private String activity = null;

  @Option(
    name = PROCESS_LONG_ARG,
    aliases = {PROCESS_SHORT_ARG},
    metaVar = "<pkg:process>",
    usage = "Process to kill after install e.g. com.facebook.katana[:proc1]. Implies -r."
  )
  @Nullable
  private String process = null;

  public AdbOptions adbOptions(BuckConfig buckConfig) {
    return adbOptions.getAdbOptions(buckConfig);
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
    return (activity != null) || (process != null) || run;
  }

  public boolean shouldInstallViaSd() {
    return installViaSd;
  }

  @Nullable
  public String getActivityToStart() {
    return activity;
  }

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {
    assertArguments(params);

    try (CommandThreadManager pool =
            new CommandThreadManager("Install", getConcurrencyLimit(params.getBuckConfig()));
        TriggerCloseable triggerCloseable = new TriggerCloseable(params)) {
      // Get the helper targets if present
      ImmutableSet<String> installHelperTargets;
      try {
        installHelperTargets = getInstallHelperTargets(params, pool.getListeningExecutorService());
      } catch (BuildFileParseException e) {
        params
            .getBuckEventBus()
            .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return ExitCode.PARSE_ERROR;
      }

      // Build the targets
      ExitCode exitCode = run(params, pool, installHelperTargets);
      if (exitCode != ExitCode.SUCCESS) {
        return exitCode;
      }
    }

    // Install the targets
    try {
      return install(params);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e.getHumanReadableErrorMessage());
    }
  }

  @Override
  protected Iterable<BuildTarget> getAdditionalTargetsToBuild(BuildRuleResolver resolver) {
    ImmutableList.Builder<BuildTarget> builder = ImmutableList.builder();
    builder.addAll(super.getAdditionalTargetsToBuild(resolver));
    for (BuildTarget target : getBuildTargets()) {
      BuildRule rule = resolver.getRule(target);
      if (rule instanceof HasInstallHelpers) {
        // An install command never explicitly "requires" the install flavor. This ensures that the
        // install flavor is always present in the action graph regardless of if it's an install or
        // a build. That ensures that the target graph and action graph are the same between install
        // and build commands.
        ((HasInstallHelpers) rule)
            .getInstallHelpers()
            .forEach(
                helper -> {
                  Preconditions.checkState(
                      resolver.getRuleOptional(helper).isPresent(),
                      "Install of %s failed because some install helpers were not available.");
                  builder.add(helper);
                });
      }
    }
    return builder.build();
  }

  private ExitCode install(CommandRunnerParams params)
      throws IOException, InterruptedException, NoSuchBuildTargetException {

    Build build = getBuild();
    ExitCode exitCode = ExitCode.SUCCESS;

    for (BuildTarget buildTarget : getBuildTargets()) {

      BuildRule buildRule = build.getRuleResolver().requireRule(buildTarget);
      SourcePathResolver pathResolver =
          DefaultSourcePathResolver.from(new SourcePathRuleFinder(build.getRuleResolver()));

      if (buildRule instanceof HasInstallableApk) {
        exitCode =
            installApk((HasInstallableApk) buildRule, getExecutionContext(), pathResolver, params);
        if (exitCode != ExitCode.SUCCESS) {
          return exitCode;
        }
      } else if (buildRule instanceof AppleBundle) {
        AppleBundle appleBundle = (AppleBundle) buildRule;
        InstallEvent.Started started = InstallEvent.started(appleBundle.getBuildTarget());
        params.getBuckEventBus().post(started);
        InstallResult installResult =
            installAppleBundle(
                params,
                appleBundle,
                appleBundle.getProjectFilesystem(),
                build.getExecutionContext().getProcessExecutor(),
                pathResolver);
        params
            .getBuckEventBus()
            .post(
                InstallEvent.finished(
                    started,
                    installResult.getExitCode() == ExitCode.SUCCESS,
                    installResult.getLaunchedPid(),
                    Optional.empty()));
        exitCode = installResult.getExitCode();
        if (exitCode != ExitCode.SUCCESS) {
          return exitCode;
        }
      } else if (!(buildRule instanceof NoopInstallable)) {
        params
            .getBuckEventBus()
            .post(
                ConsoleEvent.severe(
                    String.format(
                        "Specified rule %s must be of type android_binary() or apk_genrule() or "
                            + "apple_bundle() but was %s().\n",
                        buildRule.getFullyQualifiedName(), buildRule.getType())));
        return ExitCode.BUILD_ERROR;
      }
    }
    return exitCode;
  }

  @Override
  protected ExecutionContext.Builder getExecutionContextBuilder(CommandRunnerParams params) {
    return super.getExecutionContextBuilder(params)
        .setAndroidDevicesHelper(
            AndroidDevicesHelperFactory.get(
                params.getCell().getToolchainProvider(),
                this::getExecutionContext,
                params.getBuckConfig(),
                adbOptions(params.getBuckConfig()),
                targetDeviceOptions()));
  }

  private ImmutableSet<String> getInstallHelperTargets(
      CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException {

    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    ImmutableSet.Builder<String> installHelperTargets = ImmutableSet.builder();
    // TODO(cjhopman): This shouldn't be doing parsing outside of the normal parse stage.
    // The first step to that would be to move the Apple install helpers to be deps available from
    // getInstallHelpers() call on the installed target. Then those helpers should be available
    // without needing to explicitly request them here.
    for (int index = 0; index < getArguments().size(); index++) {
      // TODO(markwang): Cache argument parsing
      TargetNodeSpec spec =
          parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), getArguments()).get(index);

      BuildTarget target =
          FluentIterable.from(
                  params
                      .getParser()
                      .resolveTargetSpecs(
                          params.getBuckEventBus(),
                          params.getCell(),
                          getEnableParserProfiling(),
                          executor,
                          ImmutableList.of(spec),
                          PerBuildState.SpeculativeParsing.DISABLED,
                          parserConfig.getDefaultFlavorsMode()))
              .transformAndConcat(Functions.identity())
              .first()
              .get();

      TargetNode<?, ?> node =
          params
              .getParser()
              .getTargetNode(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  executor,
                  target);

      if (node != null
          && Description.getBuildRuleType(node.getDescription())
              .equals(Description.getBuildRuleType(AppleBundleDescription.class))) {
        for (Flavor flavor : node.getBuildTarget().getFlavors()) {
          if (ApplePlatform.needsInstallHelper(flavor.getName())) {
            AppleConfig appleConfig = params.getBuckConfig().getView(AppleConfig.class);

            Optional<BuildTarget> deviceHelperTarget = appleConfig.getAppleDeviceHelperTarget();
            Optionals.addIfPresent(
                Optionals.bind(
                    deviceHelperTarget,
                    input ->
                        !input.toString().isEmpty()
                            ? Optional.of(input.toString())
                            : Optional.empty()),
                installHelperTargets);
          }
        }
      }
    }
    return installHelperTargets.build();
  }

  private ExitCode installApk(
      HasInstallableApk hasInstallableApk,
      ExecutionContext executionContext,
      SourcePathResolver pathResolver,
      CommandRunnerParams params)
      throws InterruptedException {
    AndroidDevicesHelper adbHelper = executionContext.getAndroidDevicesHelper().get();

    boolean concurrentInstallEnabled = false;
    // concurrentInstall is currently only implemented for AndroidBinary (and not subclasses).
    if (hasInstallableApk.getClass().equals(AndroidBinary.class)
        && new AndroidInstallConfig(params.getBuckConfig())
            .getConcurrentInstallEnabled(Optional.of(params.getBuckEventBus()))) {
      concurrentInstallEnabled = true;
    }

    if (!concurrentInstallEnabled) {
      // Uninstall the app first, if requested.
      if (shouldUninstallFirst()) {
        String packageName =
            AdbHelper.tryToExtractPackageNameFromManifest(
                pathResolver, hasInstallableApk.getApkInfo());
        adbHelper.uninstallApp(packageName, uninstallOptions().shouldKeepUserData());
        // Perhaps the app wasn't installed to begin with, shouldn't stop us.
      }

      if (!adbHelper.installApk(
          pathResolver, hasInstallableApk, shouldInstallViaSd(), false, process)) {
        params.getConsole().printBuildFailure("Install failed.");
        return ExitCode.RUN_ERROR;
      }
    } else if (shouldUninstallFirst()) {
      // TODO(cjhopman): Figure out how to support this (maybe write some options to the trigger
      // file).
      params
          .getConsole()
          .printErrorText("concurrent_install does not support uninstalling (-u/--uninstall)");
    } else {
      // Send some of the normal events and messages so that the user receives similar messages/UI
      // as in non-concurrent install.
      // TODO(cjhopman): Figure out what to do about killing a process.
      InstallEvent.Started started = InstallEvent.started(hasInstallableApk.getBuildTarget());
      params.getBuckEventBus().post(started);
      adbHelper.adbCallOrThrow("concurrent install", (device) -> true, false);
      InstallEvent.Finished finished =
          InstallEvent.finished(
              started,
              true,
              Optional.empty(),
              Optional.of(
                  AdbHelper.tryToExtractPackageNameFromManifest(
                      pathResolver, hasInstallableApk.getApkInfo())));
      params.getBuckEventBus().post(finished);
    }

    // We've installed the application successfully.
    // Are any of --activity, --process, or --run present?
    if (shouldStartActivity()) {
      try {
        adbHelper.startActivity(
            pathResolver, hasInstallableApk, getActivityToStart(), waitForDebugger);
      } catch (HumanReadableException hre) {
        LOG.warn(hre, "Unable to start activity");
        params.getConsole().printBuildFailure(hre.getMessage());
        return ExitCode.RUN_ERROR;
      } catch (Exception e) {
        throw new BuckUncheckedExecutionException(e, "When starting activity.");
      }
    }

    return ExitCode.SUCCESS;
  }

  private InstallResult installAppleBundle(
      CommandRunnerParams params,
      AppleBundle appleBundle,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor,
      SourcePathResolver pathResolver)
      throws IOException, InterruptedException, NoSuchBuildTargetException {
    String platformName = appleBundle.getPlatformName();
    if (isSimulator(platformName)) {
      return installAppleBundleForSimulator(
          params, appleBundle, pathResolver, projectFilesystem, processExecutor);
    }
    if (isDevice(platformName)) {
      return installAppleBundleForDevice(
          params, appleBundle, projectFilesystem, processExecutor, pathResolver);
    }
    params
        .getConsole()
        .printBuildFailure(
            "Install not yet supported for platform " + appleBundle.getPlatformName() + ".");
    return FAILURE;
  }

  private InstallResult installAppleBundleForDevice(
      CommandRunnerParams params,
      AppleBundle appleBundle,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor,
      SourcePathResolver pathResolver)
      throws IOException {
    AppleConfig appleConfig = params.getBuckConfig().getView(AppleConfig.class);

    Path helperPath;
    Optional<BuildTarget> helperTarget = appleConfig.getAppleDeviceHelperTarget();
    if (helperTarget.isPresent()) {
      BuildRuleResolver resolver = getBuild().getRuleResolver();
      BuildRule buildRule = resolver.requireRule(helperTarget.get());
      if (buildRule == null) {
        params
            .getConsole()
            .printBuildFailure(
                String.format(
                    "Cannot install %s (could not resolve build rule for device helper target %s)",
                    appleBundle.getFullyQualifiedName(), helperTarget.get().getBaseName()));
        return FAILURE;
      }
      SourcePath buildRuleOutputPath = buildRule.getSourcePathToOutput();
      if (buildRuleOutputPath == null) {
        params
            .getConsole()
            .printBuildFailure(
                String.format(
                    "Cannot install %s (device helper target %s does not specify an output)",
                    appleBundle.getFullyQualifiedName(), helperTarget.get().getBaseName()));
        return FAILURE;
      }
      helperPath = pathResolver.getAbsolutePath(buildRuleOutputPath);
    } else {
      Optional<Path> helperOverridePath = appleConfig.getAppleDeviceHelperAbsolutePath();
      if (helperOverridePath.isPresent()) {
        helperPath = helperOverridePath.get();
      } else {
        params
            .getConsole()
            .printBuildFailure(
                String.format(
                    "Cannot install %s (could not find path to device install helper tool)",
                    appleBundle.getFullyQualifiedName()));
        return FAILURE;
      }
    }

    AppleDeviceHelper helper = new AppleDeviceHelper(processExecutor, helperPath);
    ImmutableMap<String, String> connectedDevices = helper.getConnectedDevices();

    if (connectedDevices.size() == 0) {
      params
          .getConsole()
          .printBuildFailure(
              String.format(
                  "Cannot install %s (no connected devices found)",
                  appleBundle.getFullyQualifiedName()));
      return FAILURE;
    }

    String selectedUdid = null;

    if (targetDeviceOptions().getSerialNumber().isPresent()) {
      String udidPrefix =
          Assertions.assertNotNull(targetDeviceOptions().getSerialNumber().get()).toLowerCase();
      for (String udid : connectedDevices.keySet()) {
        if (udid.startsWith(udidPrefix)) {
          selectedUdid = udid;
          break;
        }
      }

      if (selectedUdid == null) {
        params
            .getConsole()
            .printBuildFailure(
                String.format(
                    "Cannot install %s to the device %s (no connected devices with that UDID/prefix)",
                    appleBundle.getFullyQualifiedName(), udidPrefix));
        return FAILURE;
      }
    } else {
      if (connectedDevices.size() > 1) {
        LOG.warn(
            "More than one connected device found, and no device ID specified.  A device will be"
                + " arbitrarily picked.");
      }

      selectedUdid = connectedDevices.keySet().iterator().next();
    }

    LOG.info(
        "Installing "
            + appleBundle.getFullyQualifiedName()
            + " to device "
            + selectedUdid
            + " ("
            + connectedDevices.get(selectedUdid)
            + ")");

    if (helper.installBundleOnDevice(
        selectedUdid,
        pathResolver.getAbsolutePath(
            Preconditions.checkNotNull(appleBundle.getSourcePathToOutput())))) {
      params
          .getConsole()
          .printSuccess(
              "Installed "
                  + appleBundle.getFullyQualifiedName()
                  + " to device "
                  + selectedUdid
                  + " ("
                  + connectedDevices.get(selectedUdid)
                  + ")");
      if (run) {
        Optional<String> appleBundleId;
        try (InputStream bundlePlistStream =
            projectFilesystem.getInputStreamForRelativePath(appleBundle.getInfoPlistPath())) {
          appleBundleId =
              AppleInfoPlistParsing.getBundleIdFromPlistStream(
                  appleBundle.getInfoPlistPath(), bundlePlistStream);
        }
        if (!appleBundleId.isPresent()) {
          params
              .getConsole()
              .printBuildFailure(
                  String.format(
                      "Cannot run %s (could not get bundle ID from %s)",
                      appleBundle.getFullyQualifiedName(), appleBundle.getInfoPlistPath()));
          return FAILURE;
        }

        if (waitForDebugger) {
          LOG.warn(WAIT_FOR_DEBUGGER_LONG_ARG + " not yet implemented for devices.");
        }

        if (helper.runBundleOnDevice(selectedUdid, appleBundleId.get())) {
          return InstallResult.builder().setExitCode(ExitCode.SUCCESS).build();
        } else {
          params
              .getConsole()
              .printBuildFailure(
                  "Failed to run "
                      + appleBundle.getFullyQualifiedName()
                      + " on device "
                      + selectedUdid
                      + " ("
                      + connectedDevices.get(selectedUdid)
                      + ")");
          return FAILURE;
        }
      } else {
        return InstallResult.builder().setExitCode(ExitCode.SUCCESS).build();
      }
    } else {
      params
          .getConsole()
          .printBuildFailure(
              "Failed to install "
                  + appleBundle.getFullyQualifiedName()
                  + " to device "
                  + selectedUdid
                  + " ("
                  + connectedDevices.get(selectedUdid)
                  + ")");
      return FAILURE;
    }
  }

  private InstallResult installAppleBundleForSimulator(
      CommandRunnerParams params,
      AppleBundle appleBundle,
      SourcePathResolver pathResolver,
      ProjectFilesystem projectFilesystem,
      ProcessExecutor processExecutor)
      throws IOException, InterruptedException {

    AppleConfig appleConfig = params.getBuckConfig().getView(AppleConfig.class);
    Optional<Path> xcodeDeveloperPath =
        appleConfig.getAppleDeveloperDirectorySupplier(processExecutor).get();
    if (!xcodeDeveloperPath.isPresent()) {
      params
          .getConsole()
          .printBuildFailure(
              String.format(
                  "Cannot install %s (Xcode not found)", appleBundle.getFullyQualifiedName()));
      return FAILURE;
    }

    UnixUserIdFetcher userIdFetcher = new UnixUserIdFetcher();
    AppleCoreSimulatorServiceController appleCoreSimulatorServiceController =
        new AppleCoreSimulatorServiceController(processExecutor);

    Optional<Path> coreSimulatorServicePath =
        appleCoreSimulatorServiceController.getCoreSimulatorServicePath(userIdFetcher);

    boolean shouldWaitForSimulatorsToShutdown = false;

    if (!coreSimulatorServicePath.isPresent()
        || !coreSimulatorServicePath
            .get()
            .toRealPath()
            .startsWith(xcodeDeveloperPath.get().toRealPath())) {
      LOG.warn(
          "Core simulator service path %s does not match developer directory %s, "
              + "killing all simulators.",
          coreSimulatorServicePath, xcodeDeveloperPath.get());
      if (!appleCoreSimulatorServiceController.killSimulatorProcesses()) {
        params.getConsole().printBuildFailure("Could not kill running simulator processes.");
        return FAILURE;
      }

      shouldWaitForSimulatorsToShutdown = true;
    }

    Path simctlPath = xcodeDeveloperPath.get().resolve("usr/bin/simctl");
    Optional<AppleSimulator> appleSimulator =
        getAppleSimulatorForBundle(appleBundle, processExecutor, simctlPath);

    if (!appleSimulator.isPresent()) {
      params
          .getConsole()
          .printBuildFailure(
              String.format(
                  "Cannot install %s (no appropriate simulator found)",
                  appleBundle.getFullyQualifiedName()));
      return FAILURE;
    }

    Path iosSimulatorPath = null;
    Path xcodeApplicationsPath = xcodeDeveloperPath.get().resolve("Applications");
    for (String simulatorApp : APPLE_SIMULATOR_APPS) {
      Path resolvedSimulatorPath = xcodeApplicationsPath.resolve(simulatorApp);
      if (projectFilesystem.isDirectory(resolvedSimulatorPath)) {
        iosSimulatorPath = resolvedSimulatorPath;
        break;
      }
    }

    if (iosSimulatorPath == null) {
      params
          .getConsole()
          .printBuildFailure(
              String.format(
                  "Cannot install %s (could not find simulator under %s, checked %s)",
                  appleBundle.getFullyQualifiedName(),
                  xcodeApplicationsPath,
                  APPLE_SIMULATOR_APPS));
      return FAILURE;
    }

    AppleSimulatorController appleSimulatorController =
        new AppleSimulatorController(processExecutor, simctlPath, iosSimulatorPath);

    if (!appleSimulatorController.canStartSimulator(appleSimulator.get().getUdid())) {
      LOG.warn("Cannot start simulator %s, killing simulators and trying again.");
      if (!appleCoreSimulatorServiceController.killSimulatorProcesses()) {
        params.getConsole().printBuildFailure("Could not kill running simulator processes.");
        return FAILURE;
      }

      shouldWaitForSimulatorsToShutdown = true;

      // Killing the simulator can cause the UDIDs to change, so we need to fetch them again.
      appleSimulator = getAppleSimulatorForBundle(appleBundle, processExecutor, simctlPath);
      if (!appleSimulator.isPresent()) {
        params
            .getConsole()
            .printBuildFailure(
                String.format(
                    "Cannot install %s (no appropriate simulator found)",
                    appleBundle.getFullyQualifiedName()));
        return FAILURE;
      }
    }

    long remainingMillis = APPLE_SIMULATOR_WAIT_MILLIS;
    if (shouldWaitForSimulatorsToShutdown) {
      Optional<Long> shutdownMillis =
          appleSimulatorController.waitForSimulatorsToShutdown(remainingMillis);
      if (!shutdownMillis.isPresent()) {
        params
            .getConsole()
            .printBuildFailure(
                String.format(
                    "Cannot install %s (simulators did not shut down within %d ms).",
                    appleBundle.getFullyQualifiedName(), APPLE_SIMULATOR_WAIT_MILLIS));
        return FAILURE;
      }

      LOG.debug("Simulators shut down in %d millis.", shutdownMillis.get());
      remainingMillis -= shutdownMillis.get();
    }

    LOG.debug("Starting up simulator %s", appleSimulator.get());

    Optional<Long> startMillis =
        appleSimulatorController.startSimulator(appleSimulator.get().getUdid(), remainingMillis);

    if (!startMillis.isPresent()) {
      params
          .getConsole()
          .printBuildFailure(
              String.format(
                  "Cannot install %s (could not start simulator %s within %d ms)",
                  appleBundle.getFullyQualifiedName(),
                  appleSimulator.get().getName(),
                  APPLE_SIMULATOR_WAIT_MILLIS));
      return FAILURE;
    }

    LOG.debug(
        "Simulator started in %d ms. Installing Apple bundle %s in simulator %s",
        startMillis.get(), appleBundle, appleSimulator.get());

    if (!appleSimulatorController.installBundleInSimulator(
        appleSimulator.get().getUdid(),
        pathResolver.getAbsolutePath(
            Preconditions.checkNotNull(appleBundle.getSourcePathToOutput())))) {
      params
          .getConsole()
          .printBuildFailure(
              String.format(
                  "Cannot install %s (could not install bundle %s in simulator %s)",
                  appleBundle.getFullyQualifiedName(),
                  pathResolver.getAbsolutePath(appleBundle.getSourcePathToOutput()),
                  appleSimulator.get().getName()));
      return FAILURE;
    }

    if (run) {
      return launchAppleBundle(
          params, appleBundle, appleSimulatorController, projectFilesystem, appleSimulator.get());
    } else {
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.info(
                  params
                      .getConsole()
                      .getAnsi()
                      .asHighlightedSuccessText(
                          "Successfully installed %s. (Use `buck install -r %s` to run.)"),
                  getArguments().get(0),
                  getArguments().get(0)));
      return InstallResult.builder().setExitCode(ExitCode.SUCCESS).build();
    }
  }

  private InstallResult launchAppleBundle(
      CommandRunnerParams params,
      AppleBundle appleBundle,
      AppleSimulatorController appleSimulatorController,
      ProjectFilesystem projectFilesystem,
      AppleSimulator appleSimulator)
      throws IOException, InterruptedException {

    LOG.debug("Launching Apple bundle %s in simulator %s", appleBundle, appleSimulator);

    Optional<String> appleBundleId;
    try (InputStream bundlePlistStream =
        projectFilesystem.getInputStreamForRelativePath(appleBundle.getInfoPlistPath())) {
      appleBundleId =
          AppleInfoPlistParsing.getBundleIdFromPlistStream(
              appleBundle.getInfoPlistPath(), bundlePlistStream);
    }
    if (!appleBundleId.isPresent()) {
      params
          .getConsole()
          .printBuildFailure(
              String.format(
                  "Cannot install %s (could not get bundle ID from %s)",
                  appleBundle.getFullyQualifiedName(), appleBundle.getInfoPlistPath()));
      return FAILURE;
    }

    Optional<Long> launchedPid =
        appleSimulatorController.launchInstalledBundleInSimulator(
            appleSimulator.getUdid(),
            appleBundleId.get(),
            waitForDebugger
                ? AppleSimulatorController.LaunchBehavior.WAIT_FOR_DEBUGGER
                : AppleSimulatorController.LaunchBehavior.DO_NOT_WAIT_FOR_DEBUGGER,
            runArgs);
    if (!launchedPid.isPresent()) {
      params
          .getConsole()
          .printBuildFailure(
              String.format(
                  "Cannot launch %s (failed to launch bundle ID %s)",
                  appleBundle.getFullyQualifiedName(), appleBundleId.get()));
      return FAILURE;
    }

    params
        .getBuckEventBus()
        .post(
            ConsoleEvent.info(
                params
                    .getConsole()
                    .getAnsi()
                    .asHighlightedSuccessText(
                        "Successfully launched %s%s. To debug, run: lldb -p %d"),
                getArguments().get(0),
                waitForDebugger ? " (waiting for debugger)" : "",
                launchedPid.get()));

    return InstallResult.builder()
        .setExitCode(ExitCode.SUCCESS)
        .setLaunchedPid(launchedPid.get())
        .build();
  }

  private Optional<AppleSimulator> getAppleSimulatorForBundle(
      AppleBundle appleBundle, ProcessExecutor processExecutor, Path simctlPath)
      throws IOException, InterruptedException {
    LOG.debug("Choosing simulator for %s", appleBundle);

    Optional<AppleSimulator> simulatorByUdid = Optional.empty();
    Optional<AppleSimulator> simulatorByName = Optional.empty();
    Optional<AppleSimulator> defaultSimulator = Optional.empty();

    boolean wantUdid = deviceOptions.getSerialNumber().isPresent();
    boolean wantName = deviceOptions.getSimulatorName().isPresent();

    for (AppleSimulator simulator :
        AppleSimulatorDiscovery.discoverAppleSimulators(processExecutor, simctlPath)) {
      if (wantUdid
          && deviceOptions
              .getSerialNumber()
              .get()
              .toLowerCase(Locale.US)
              .equals(simulator.getUdid().toLowerCase(Locale.US))) {
        LOG.debug("Got UDID match (%s): %s", deviceOptions.getSerialNumber().get(), simulator);
        simulatorByUdid = Optional.of(simulator);
        // We shouldn't need to keep looking.
        break;
      } else if (wantName
          && deviceOptions
              .getSimulatorName()
              .get()
              .toLowerCase(Locale.US)
              .equals(simulator.getName().toLowerCase(Locale.US))) {
        LOG.debug("Got name match (%s): %s", simulator.getName(), simulator);
        simulatorByName = Optional.of(simulator);
        // We assume the simulators are sorted by OS version, so we'll keep
        // looking for a more recent simulator with this name.
      } else if (isIPhoneSimulator(appleBundle.getPlatformName())
          && simulator.getName().equals(DEFAULT_APPLE_SIMULATOR_NAME)) {
        LOG.debug("Got default match (%s): %s", DEFAULT_APPLE_SIMULATOR_NAME, simulator);
        defaultSimulator = Optional.of(simulator);
      } else if (isAppleTVSimulator(appleBundle.getPlatformName())
          && simulator.getName().equals(DEFAULT_APPLE_TV_SIMULATOR_NAME)) {
        LOG.debug("Got default match (%s): %s", DEFAULT_APPLE_TV_SIMULATOR_NAME, simulator);
        defaultSimulator = Optional.of(simulator);
      }
    }

    if (wantUdid) {
      if (simulatorByUdid.isPresent()) {
        return simulatorByUdid;
      } else {
        LOG.warn(
            "Asked to find simulator with UDID %s, but couldn't find one.",
            deviceOptions.getSerialNumber().get());
        return Optional.empty();
      }
    } else if (wantName) {
      if (simulatorByName.isPresent()) {
        return simulatorByName;
      } else {
        LOG.warn(
            "Asked to find simulator with name %s, but couldn't find one.",
            deviceOptions.getSimulatorName().get());
        return Optional.empty();
      }
    } else {
      return defaultSimulator;
    }
  }

  @Override
  public String getShortDescription() {
    return "builds and installs an application";
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  private boolean isSimulator(String platformName) {
    return platformName.equals(ApplePlatform.IPHONESIMULATOR.getName())
        || platformName.equals(ApplePlatform.APPLETVSIMULATOR.getName());
  }

  private boolean isDevice(String platformName) {
    return platformName.equals(ApplePlatform.IPHONEOS.getName());
  }

  private boolean isIPhoneSimulator(String platformName) {
    return platformName.equals(ApplePlatform.IPHONESIMULATOR.getName());
  }

  private boolean isAppleTVSimulator(String platformName) {
    return platformName.equals(ApplePlatform.APPLETVSIMULATOR.getName());
  }

  private static class TriggerCloseable implements Closeable {
    private final Cell root;
    private final CommandRunnerParams params;
    private final Closer closer;

    TriggerCloseable(CommandRunnerParams params) throws IOException {
      this.params = params;
      this.root = params.getCell();
      this.closer = Closer.create();
      for (Cell cell : root.getAllCells()) {
        invalidateTrigger(cell);
        closer.register(
            () -> {
              invalidateTrigger(cell);
              Files.deleteIfExists(getAbsoluteTriggerPath(cell));
            });
        Path absoluteTriggerPath = getAbsoluteTriggerPath(cell);
        cell.getFilesystem().createParentDirs(absoluteTriggerPath);
        Files.write(
            absoluteTriggerPath, params.getBuckEventBus().getBuildId().toString().getBytes());
      }
    }

    @Override
    public void close() throws IOException {
      closer.close();
    }

    // TODO(cjhopman): This is a bit of a hack to properly invalidate all cached data. It would be
    // nice if there were a more elegant way to do this.
    private void invalidateTrigger(Cell cell) {
      // This is necessary to invalidate all rulekeys that transitively depend on the trigger.
      params
          .getDefaultRuleKeyFactoryCacheRecycler()
          .ifPresent(
              recycler -> recycler.invalidatePath(cell.getFilesystem(), getTriggerPath(cell)));
      // This invalidates the cached file hash in the filehashcache.
      params.getFileHashCache().invalidate(getAbsoluteTriggerPath(cell));
    }

    private static Path getTriggerPath(Cell cell) {
      return InstallTrigger.getTriggerPath(cell.getFilesystem());
    }

    private static Path getAbsoluteTriggerPath(Cell cell) {
      return cell.getFilesystem().resolve(getTriggerPath(cell));
    }
  }
}
