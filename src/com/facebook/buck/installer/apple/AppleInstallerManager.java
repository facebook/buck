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

import com.facebook.buck.apple.simulator.AppleDeviceController;
import com.facebook.buck.installer.InstallCommand;
import com.facebook.buck.installer.InstallId;
import com.facebook.buck.installer.InstallResult;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.SettableFuture;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD

/** Apple install manager */
class AppleInstallerManager implements InstallCommand {

  private static final Console CONSOLE =
      new Console(Verbosity.STANDARD_INFORMATION, System.out, System.err, Ansi.withoutTty());

  public final Map<InstallId, SettableFuture<AppleInstallAppOptions>> installIdToOptionsMap =
      new HashMap<>();
  private final Logger logger;
  private final AppleCommandLineOptions options;

  public AppleInstallerManager(Logger logger, AppleCommandLineOptions options) {
    this.logger = logger;
    this.options = options;
  }

  @Override
  public InstallResult install(String artifactName, Path artifactPath, InstallId installId) {
    SettableFuture<AppleInstallAppOptions> appInstallOptionsFuture = getOptionsFuture(installId);
    // if options file
    if (artifactName.equals("options")) {
      try {
        appInstallOptionsFuture.set(new AppleInstallAppOptions(artifactPath));
        return InstallResult.success();
      } catch (Exception err) {
        String errMsg = Throwables.getStackTraceAsString(err);
        logger.log(
            Level.SEVERE,
            String.format(
                "Error creating AppleInstallAppOptions from `install_apple_data`. Error message: %s",
                errMsg),
            err);
        return InstallResult.error(errMsg);
      }
    }

    // process app installation
    try {
      // wait till ready
      AppleInstallAppOptions appleInstallAppOptions = appInstallOptionsFuture.get();

      DefaultProcessExecutor processExecutor = new DefaultProcessExecutor(CONSOLE);
      AppleDeviceController appleDeviceController =
          new AppleDeviceController(processExecutor, Paths.get("/usr/local/bin/idb"));
      AppleInstall appleInstall =
          new AppleInstall(
              processExecutor,
              options,
              appleDeviceController,
              appleInstallAppOptions,
              logger,
              artifactPath);
      return appleInstall.installAppleBundle(artifactPath);
    } catch (Exception err) {
      String errMsg = Throwables.getStackTraceAsString(err);
      return InstallResult.error(errMsg);
    }
  }

  private SettableFuture<AppleInstallAppOptions> getOptionsFuture(InstallId installId) {
    synchronized (installIdToOptionsMap) {
      return installIdToOptionsMap.computeIfAbsent(installId, ignore -> SettableFuture.create());
    }
  }
}
