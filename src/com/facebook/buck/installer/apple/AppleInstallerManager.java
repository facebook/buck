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
import com.facebook.buck.installer.InstallResult;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.util.concurrent.SettableFuture;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD

/** Apple install manager */
class AppleInstallerManager implements InstallCommand {

  private static volatile AppleInstallerManager instance;
  public final ConcurrentHashMap<String, SettableFuture<AppleInstallAppOptions>> chm =
      new ConcurrentHashMap<>();
  private Logger log;
  private AppleCommandLineOptions cliOpts;

  @Override
  public InstallResult install(String name, Path appPath) {
    SettableFuture<AppleInstallAppOptions> appInstallOptions;
    if (appPath.endsWith(Paths.get("install_apple_data.json"))) {
      name = name.replaceAll("options_", "");
      synchronized (chm) {
        appInstallOptions = getOrMakeAppleInstallAppOptions(name);
      }
      try {
        JsonParser parser = ObjectMappers.createParser(appPath);
        Map<String, String> jsonData =
            parser.readValueAs(new TypeReference<TreeMap<String, String>>() {});
        appInstallOptions.set(new AppleInstallAppOptions(jsonData));
        return InstallResult.success();
      } catch (Exception err) {
        log.log(Level.SEVERE, "Error creating AppleInstallAppOptions from `install_apple_data`");
        return InstallResult.error(err.toString());
      }
    } else {
      try {
        synchronized (chm) {
          appInstallOptions = getOrMakeAppleInstallAppOptions(name);
        }
        appInstallOptions.get();
        Console console =
            new Console(Verbosity.STANDARD_INFORMATION, System.out, System.err, new Ansi(false));
        DefaultProcessExecutor processExecutor = new DefaultProcessExecutor(console);
        AppleDeviceController appleDeviceController =
            new AppleDeviceController(processExecutor, Paths.get("/usr/local/bin/idb"));
        AppleInstall appleInstall =
            new AppleInstall(
                processExecutor,
                this.cliOpts,
                appleDeviceController,
                appInstallOptions.get(),
                this.log,
                appPath);
        return appleInstall.installAppleBundle(appPath);
      } catch (Exception err) {
        return InstallResult.error(err.toString());
      }
    }
  }

  private SettableFuture<AppleInstallAppOptions> getOrMakeAppleInstallAppOptions(String name) {
    if (chm.get(name) != null) {
      return chm.get(name);
    } else {
      SettableFuture<AppleInstallAppOptions> opts = SettableFuture.create();
      chm.put(name, opts);
      return opts;
    }
  }

  static AppleInstallerManager getInstance() {
    if (instance == null) {
      synchronized (AppleInstallerManager.class) {
        if (instance == null) {
          instance = new AppleInstallerManager();
        }
      }
    }
    return instance;
  }

  void setLogger(Logger log) {
    this.log = log;
  }

  void setCLIOptions(AppleCommandLineOptions cliOpts) {
    this.cliOpts = cliOpts;
  }
}
