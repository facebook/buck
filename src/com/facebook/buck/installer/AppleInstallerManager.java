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

package com.facebook.buck.installer;

import com.facebook.buck.apple.simulator.AppleDeviceController;
import com.facebook.buck.util.*;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD

class AppleInstallerManager extends InstallType {
  private static volatile AppleInstallerManager instance;
  public Path idbPath;
  public final ConcurrentHashMap<String, SettableFuture<AppleInstallAppOptions>> chm =
      new ConcurrentHashMap<>();
  private Logger log;
  private AppleCommandLineOptions cliOpts;
  private String root;

  @Override
  public InstallResult install(String name, Path path) throws IOException, InterruptedException {
    SettableFuture<AppleInstallAppOptions> appInstallOptions;
    if (path.endsWith(Paths.get("install_apple_data.json"))) {
      name = name.replaceAll("options_", "");
      synchronized (chm) {
        appInstallOptions = getorMakeAppleInstallAppOptions(name);
      }
      try {
        JsonParser parser = ObjectMappers.createParser(path);
        Map<String, String> json_data =
            parser.readValueAs(new TypeReference<TreeMap<String, String>>() {});
        AppleInstallAppOptions app = new AppleInstallAppOptions(json_data);
        appInstallOptions.set(app);
        return new InstallResult(false, new String(""));
      } catch (Exception err) {
        log.log(Level.SEVERE, "Error creating AppleInstallAppOptions from `install_apple_data`");
        return new InstallResult(true, err.toString());
      }
    } else {
      try {
        synchronized (chm) {
          appInstallOptions = getorMakeAppleInstallAppOptions(name);
        }
        appInstallOptions.get();
        Console con =
            new Console(Verbosity.STANDARD_INFORMATION, System.out, System.err, new Ansi(false));
        DefaultProcessExecutor processExecutor = new DefaultProcessExecutor(con);
        AppleDeviceController appleDeviceController =
            new AppleDeviceController(processExecutor, Paths.get("/usr/local/bin/idb"));
        AppleInstall appleInstall =
            new AppleInstall(
                processExecutor,
                this.cliOpts,
                appleDeviceController,
                appInstallOptions.get(),
                this.log,
                path);
        return appleInstall.installAppleBundle(path);
      } catch (Exception err) {
        return new InstallResult(true, err.toString());
      }
    }
  }

  public SettableFuture<AppleInstallAppOptions> getorMakeAppleInstallAppOptions(String name) {
    if (chm.get(name) != null) {
      return chm.get(name);
    } else {
      SettableFuture<AppleInstallAppOptions> opts = SettableFuture.create();
      chm.put(name, opts);
      return opts;
    }
  }

  public static AppleInstallerManager getInstance() {
    if (instance == null) {
      synchronized (AppleInstallerManager.class) {
        if (instance == null) {
          instance = new AppleInstallerManager();
        }
      }
    }
    return instance;
  }

  public void setLogger(Logger log) {
    this.log = log;
  }

  public void setCLIOptions(AppleCommandLineOptions cliOpts) {
    this.cliOpts = cliOpts;
  }
}
