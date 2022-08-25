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

package com.facebook.buck.installer.android;

import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.android.IsolatedAdbExecutionContext;
import com.facebook.buck.android.device.TargetDeviceOptions;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.installer.InstallId;
import com.facebook.buck.installer.InstallResult;
import com.facebook.buck.step.AdbOptions;
import com.google.common.base.Throwables;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD

/** Installs an Android Apk */
class AndroidInstall {

  private final AbsPath rootPath;
  private final AbsPath manifestPath;
  private final AbsPath apkPath;
  private final InstallId installId;
  private final boolean installViaSd = false;
  private final Logger logger;
  private final AdbHelper adbHelper;

  public AndroidInstall(
      Logger logger,
      AbsPath rootPath,
      AndroidCommandLineOptions cliOptions,
      AndroidInstallApkOptions apkOptions,
      AbsPath manifestPath,
      String adbExecutable,
      AbsPath apkPath,
      InstallId installId) {
    this.logger = logger;
    this.rootPath = rootPath;
    this.manifestPath = manifestPath;
    this.apkPath = apkPath;
    this.installId = installId;

    // Set-up adbOptions
    AdbOptions adbOptions =
        new AdbOptions(
            cliOptions.adbThreadCount, cliOptions.multiInstallMode, cliOptions.adbTimeout);
    TargetDeviceOptions targetDeviceOptions =
        new TargetDeviceOptions(
            cliOptions.useEmulatorsOnlyMode,
            cliOptions.useRealDevicesOnlyMode,
            Optional.ofNullable(cliOptions.serialNumber));

    this.adbHelper =
        new AdbHelper(
            adbOptions,
            targetDeviceOptions,
            new IsolatedAdbExecutionContext(),
            new IsolatedAndroidInstallerPrinter(logger),
            Optional.of(adbExecutable),
            apkOptions.restartAdbOnFailure,
            apkOptions.skipInstallMetadata,
            apkOptions.alwaysUseJavaAgent,
            apkOptions.isZstdCompressionEnabled,
            apkOptions.agentPortBase);
  }

  /** Uses AdbHelper to do actual install with APK */
  public synchronized InstallResult installApk() {
    try {
      logger.info(String.format("Attempting install of %s", apkPath));
      adbHelper.installApk(
          manifestPath, apkPath, rootPath, installViaSd, /*quiet=*/ false, installId.getValue());
    } catch (Exception err) {
      String errMsg = Throwables.getStackTraceAsString(err);
      logger.log(
          Level.SEVERE,
          String.format("Error while installing %s. Error message: %s", installId, errMsg),
          err);
      return InstallResult.error(errMsg);
    }
    return InstallResult.success();
  }
}
