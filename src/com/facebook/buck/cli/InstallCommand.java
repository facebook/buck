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

import com.facebook.buck.cli.UninstallCommand.UninstallOptions;
import com.facebook.buck.command.Build;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ExopackageInfo;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import org.kohsuke.args4j.Option;

import java.io.IOException;

import javax.annotation.Nullable;

/**
 * Command so a user can build and install an APK.
 */
public class InstallCommand extends BuildCommand {

  @VisibleForTesting static final String RUN_LONG_ARG = "--run";
  @VisibleForTesting static final String RUN_SHORT_ARG = "-r";
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
  private AdbOptions adbOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private TargetDeviceOptions deviceOptions;

  @Option(
      name = RUN_LONG_ARG,
      aliases = { RUN_SHORT_ARG },
      usage = "Run an activity (the default activity for package unless -a is specified).")
  private boolean run = false;

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
    return adbOptions;
  }

  public TargetDeviceOptions targetDeviceOptions() {
    return deviceOptions;
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
          "Must specify exactly one android_binary() or apk_genrule() rule.");
      return 1;
    }

    // Build the specified target.
    int exitCode = super.runWithoutHelp(params);
    if (exitCode != 0) {
      return exitCode;
    }

    // Get the build rule that was built. Verify that it is an android_binary() rule.
    Build build = super.getBuild();
    ActionGraph graph = build.getActionGraph();
    BuildRule buildRule = Preconditions.checkNotNull(
        graph.findBuildRuleByTarget(getBuildTargets().get(0)));
    if (!(buildRule instanceof InstallableApk)) {
      params.getConsole().printBuildFailure(String.format(
              "Specified rule %s must be of type android_binary() or apk_genrule() but was %s().\n",
              buildRule.getFullyQualifiedName(),
              buildRule.getType()));
      return 1;
    }
    InstallableApk installableApk = (InstallableApk) buildRule;

    final AdbHelper adbHelper = new AdbHelper(
        adbOptions(),
        targetDeviceOptions(),
        build.getExecutionContext(),
        params.getConsole(),
        params.getBuckEventBus(),
        params.getBuckConfig());

    // Uninstall the app first, if requested.
    if (shouldUninstallFirst()) {
      String packageName = AdbHelper.tryToExtractPackageNameFromManifest(
          installableApk,
          build.getExecutionContext());
      adbHelper.uninstallApp(packageName, uninstallOptions());
      // Perhaps the app wasn't installed to begin with, shouldn't stop us.
    }

    boolean installSuccess;
    Optional<ExopackageInfo> exopackageInfo = installableApk.getExopackageInfo();
    if (exopackageInfo.isPresent()) {
      installSuccess = new ExopackageInstaller(
          build.getExecutionContext(),
          adbHelper,
          installableApk)
          .install();
    } else {
      installSuccess = adbHelper.installApk(installableApk, this);
    }
    if (!installSuccess) {
      return 1;
    }

    // We've installed the application successfully.
    // Is either of --activity or --run present?
    if (shouldStartActivity()) {
      exitCode = adbHelper.startActivity(installableApk, getActivityToStart());
      if (exitCode != 0) {
        return exitCode;
      }
    }

    return exitCode;
  }

  @Override
  public String getShortDescription() {
    return "builds and installs an APK";
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

}
