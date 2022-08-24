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

  private static volatile AndroidInstallerManager instance;
  private AndroidCommandLineOptions options;
  private Logger logger;

  public final Map<InstallId, AndroidArtifacts> installIdToFutureMap = new HashMap<>();

  /**
   * Coordinates the install artifacts needed for an install. The install_android_options.json is
   * parsed into a AndroidInstallApkOptions and the manifest is later set in the apkInstallOptions
   * as a separate field.
   */
  @Override
  public InstallResult install(String artifactName, Path artifactPath, InstallId installId) {
    try {
      AndroidArtifacts androidArtifacts = getOrMakeAndroidArtifacts(installId);
      if (artifactName.equals("options")) {
        androidArtifacts.setApkOptions(new AndroidInstallApkOptions(artifactPath));
        return InstallResult.success();
      }

      if (artifactName.equals("manifest")) {
        androidArtifacts.setAndroidManifestPath(AbsPath.of(artifactPath));
        return InstallResult.success();
      }

      // apk processing
      logger.info(String.format("Apk processing start waiting. Artifact name: %s", artifactName));
      androidArtifacts.waitTillReadyToUse();
      logger.info(
          String.format(
              "Apk processing finished waiting. Ready to process. Artifact name: %s",
              artifactName));

      AndroidInstall androidInstaller =
          new AndroidInstall(
              logger,
              // TODO: msemko need for exo packaging support
              null,
              options,
              androidArtifacts.getApkOptions(),
              androidArtifacts.getAndroidManifestPath(),
              // TODO: msemko read from toolchains. Need to pass from buck2
              "/opt/android_sdk/platform-tools/adb",
              AbsPath.of(artifactPath),
              installId);
      return androidInstaller.installApk();

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

  public static AndroidInstallerManager getInstance() {
    if (instance == null) {
      synchronized (AndroidInstallerManager.class) {
        if (instance == null) {
          instance = new AndroidInstallerManager();
        }
      }
    }
    return instance;
  }

  private AndroidArtifacts getOrMakeAndroidArtifacts(InstallId install_id) {
    synchronized (installIdToFutureMap) {
      return installIdToFutureMap.computeIfAbsent(install_id, ignore -> new AndroidArtifacts());
    }
  }

  public void setCLIOptions(AndroidCommandLineOptions options) {
    this.options = options;
  }

  public void setLogger(Logger logger) {
    this.logger = logger;
  }
}
