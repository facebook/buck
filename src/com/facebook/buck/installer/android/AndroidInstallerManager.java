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

import com.facebook.buck.android.HasInstallableApk;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.installer.InstallCommand;
import com.facebook.buck.installer.InstallId;
import com.facebook.buck.installer.InstallResult;
import com.google.common.base.Throwables;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD

/**
 * Coordinates an Android Install of an APK. We need three artifacts: the apk,
 * install_android_options.json (configuration options), and an AndroidManifest.xml
 */
class AndroidInstallerManager implements InstallCommand {

  private final AndroidCommandLineOptions options;
  private final Logger logger;
  private final Map<InstallId, AndroidArtifacts> installIdToFutureMap = new HashMap<>();

  AndroidInstallerManager(Logger logger, AndroidCommandLineOptions options) {
    this.logger = logger;
    this.options = options;
  }

  /**
   * Coordinates the install artifacts needed for an install. The install_android_options.json is
   * parsed into a AndroidInstallApkOptions and the manifest is later set in the apkInstallOptions
   * as a separate field.
   */
  @Override
  public InstallResult fileReady(String artifactName, Path artifactPath, InstallId installId) {
    try {
      AndroidArtifacts androidArtifacts = getOrMakeAndroidArtifacts(installId);
      if (artifactName.equals("options")) {
        androidArtifacts.setApkOptions(new AndroidInstallApkOptions(artifactPath));
      } else if (artifactName.equals("manifest")) {
        androidArtifacts.setAndroidManifestPath(AbsPath.of(artifactPath));
      } else {
        androidArtifacts.setApk(AbsPath.of(artifactPath));
      }

      return InstallResult.success();
    } catch (Exception err) {
      String errMsg = Throwables.getStackTraceAsString(err);
      logger.log(
          Level.SEVERE,
          String.format(
              "Error installing %s from %s due to %s", artifactName, artifactPath, errMsg),
          err);
      return InstallResult.error(errMsg);
    }
  }

  @Override
  public InstallResult allFilesReady(InstallId installId) {
    try {
      AndroidArtifacts androidArtifacts = getOrMakeAndroidArtifacts(installId);
      AndroidInstall androidInstaller =
          new AndroidInstall(
              logger,
              // TODO: msemko need for exo packaging support
              null,
              options,
              androidArtifacts.getApkOptions(),
              HasInstallableApk.IsolatedApkInfo.of(
                  androidArtifacts.getAndroidManifestPath(), androidArtifacts.getApk()),
              // TODO: msemko read from toolchains. Need to pass from buck2
              "/opt/android_sdk/platform-tools/adb",
              installId);
      return androidInstaller.installApk();

    } catch (Exception err) {
      String errMsg = Throwables.getStackTraceAsString(err);
      logger.log(Level.SEVERE, String.format("Install error due to %s", errMsg), err);
      return InstallResult.error(errMsg);
    }
  }

  private AndroidArtifacts getOrMakeAndroidArtifacts(InstallId install_id) {
    synchronized (installIdToFutureMap) {
      return installIdToFutureMap.computeIfAbsent(install_id, ignore -> new AndroidArtifacts());
    }
  }
}
