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

import com.facebook.buck.command.Build;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.InstallableApk;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.io.IOException;

/**
 * Command so a user can build and install an APK.
 */
public class InstallCommand extends AbstractCommandRunner<InstallCommandOptions> {

  public InstallCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  InstallCommandOptions createOptions(BuckConfig buckConfig) {
    return new InstallCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(InstallCommandOptions options)
      throws IOException, InterruptedException {
    // Make sure that only one build target is specified.
    if (options.getArguments().size() != 1) {
      getStdErr().println("Must specify exactly one android_binary() or apk_genrule() rule.");
      return 1;
    }

    // Build the specified target.
    BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());
    int exitCode = buildCommand.runCommandWithOptions(options);
    if (exitCode != 0) {
      return exitCode;
    }

    // Get the build rule that was built. Verify that it is an android_binary() rule.
    Build build = buildCommand.getBuild();
    ActionGraph graph = build.getActionGraph();
    BuildRule buildRule =
        Preconditions.checkNotNull(
            graph.findBuildRuleByTarget(buildCommand.getBuildTargets().get(0)));
    if (buildRule == null || !(buildRule instanceof InstallableApk)) {
      console.printBuildFailure(String.format(
          "Specified rule %s must be of type android_binary() or apk_genrule() but was %s().\n",
          buildRule.getFullyQualifiedName(),
          buildRule.getType().getName()));
      return 1;
    }
    InstallableApk installableApk = (InstallableApk) buildRule;

    final AdbHelper adbHelper = new AdbHelper(
        options.adbOptions(),
        options.targetDeviceOptions(),
        build.getExecutionContext(),
        console,
        getBuckEventBus(),
        options.getBuckConfig());

    // Uninstall the app first, if requested.
    if (options.shouldUninstallFirst()) {
      String packageName = AdbHelper.tryToExtractPackageNameFromManifest(
          installableApk, build.getExecutionContext());
      adbHelper.uninstallApp(
          packageName,
          options.uninstallOptions());
      // Perhaps the app wasn't installed to begin with, shouldn't stop us.
    }

    boolean installSuccess;
    Optional<InstallableApk.ExopackageInfo> exopackageInfo = installableApk.getExopackageInfo();
    if (exopackageInfo.isPresent()) {
      installSuccess = new ExopackageInstaller(
          build.getExecutionContext(),
          adbHelper,
          installableApk)
          .install();
    } else {
      installSuccess = adbHelper.installApk(installableApk, options);
    }
    if (!installSuccess) {
      return 1;
    }

    // We've installed the application successfully.
    // Is either of --activity or --run present?
    if (options.shouldStartActivity()) {
      exitCode = adbHelper.startActivity(
          installableApk,
          options.getActivityToStart());
      if (exitCode != 0) {
        return exitCode;
      }
    }

    return exitCode;
  }

  @Override
  String getUsageIntro() {
    return "Specify an android_binary() rule whose APK should be installed";
  }
}
