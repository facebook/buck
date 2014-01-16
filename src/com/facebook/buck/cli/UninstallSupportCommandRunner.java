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

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.facebook.buck.cli.UninstallCommandOptions.UninstallOptions;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.DefaultAndroidManifestReader;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Base class for Commands which need {@link AndroidDebugBridge} and also need to
 * uninstall packages.
 */
public abstract class UninstallSupportCommandRunner<T extends AbstractCommandOptions>
    extends AdbCommandRunner<T> {

  protected UninstallSupportCommandRunner(CommandRunnerParams params) {
    super(params);
  }

  /**
   * Uninstall apk from all matching devices.
   *
   * @see InstallCommand#installApk(com.facebook.buck.rules.InstallableApk, InstallCommandOptions, ExecutionContext)
   */
  @VisibleForTesting
  protected boolean uninstallApk(final String packageName,
      final AdbOptions adbOptions,
      final TargetDeviceOptions deviceOptions,
      final UninstallOptions uninstallOptions,
      ExecutionContext context,
      BuckConfig buckConfig) {
    getBuckEventBus().post(UninstallEvent.started(packageName));
    boolean success = adbCall(adbOptions, deviceOptions, context, new AdbCallable() {
      @Override
      public boolean call(IDevice device) throws Exception {
        return uninstallApkFromDevice(device, packageName, uninstallOptions.shouldKeepUserData());
      }

      @Override
      public String toString() {
        return "uninstall apk";
      }
    },
    buckConfig);
    getBuckEventBus().post(UninstallEvent.finished(packageName, success));
    return success;
  }

  /**
   * Uninstalls apk from specific device. Reports success or failure to console.
   * It's currently here because it's used both by {@link InstallCommand} and
   * {@link UninstallCommand}.
   */
  @VisibleForTesting
  @SuppressWarnings("PMD.PrematureDeclaration")
  protected boolean uninstallApkFromDevice(IDevice device, String packageName, boolean keepData) {
    String name;
    if (device.isEmulator()) {
      name = device.getSerialNumber() + " (" + device.getAvdName() + ")";
    } else {
      name = device.getSerialNumber();
      String model = device.getProperty("ro.product.model");
      if (model != null) {
        name += " (" + model + ")";
      }
    }

    PrintStream stdOut = console.getStdOut();
    stdOut.printf("Removing apk from %s.\n", name);
    try {
      long start = System.currentTimeMillis();
      String reason = deviceUninstallPackage(device, packageName, keepData);
      long end = System.currentTimeMillis();

      if (reason != null) {
        console.printBuildFailure(String.format("Failed to uninstall apk from %s: %s.", name, reason));
        return false;
      }

      long delta = end - start;
      stdOut.printf("Uninstalled apk from %s in %d.%03ds.\n", name, delta / 1000, delta % 1000);
      return true;

    } catch (InstallException ex) {
      console.printBuildFailure(String.format("Failed to uninstall apk from %s.", name));
      ex.printStackTrace(console.getStdErr());
      return false;
    }
  }

  /**
   * Modified version of <a href="http://fburl.com/8840769">Device.uninstallPackage()</a>.
   *
   * @param device an {@link IDevice}
   * @param packageName application package name
   * @param keepData  true if user data is to be kept
   * @return error message or null if successful
   * @throws InstallException
   */
  private String deviceUninstallPackage(IDevice device,
      String packageName,
      boolean keepData) throws InstallException {
    try {
      ErrorParsingReceiver receiver = new ErrorParsingReceiver() {
        @Override
        protected String matchForError(String line) {
          return line.toLowerCase().contains("failure") ? line : null;
        }
      };
      device.executeShellCommand(
          "pm uninstall " + (keepData ? "-k " : "") + packageName,
          receiver,
          INSTALL_TIMEOUT);
      return receiver.getErrorMessage();
    } catch (TimeoutException e) {
      throw new InstallException(e);
    } catch (AdbCommandRejectedException e) {
      throw new InstallException(e);
    } catch (ShellCommandUnresponsiveException e) {
      throw new InstallException(e);
    } catch (IOException e) {
      throw new InstallException(e);
    }
  }

  String tryToExtractPackageNameFromManifest(InstallableApk androidBinaryRule,
      DependencyGraph dependencyGraph) {
    String pathToManifest = androidBinaryRule.getManifest().resolve(dependencyGraph).toString();

    // Note that the file may not exist if AndroidManifest.xml is a generated file.
    File androidManifestXml = new File(pathToManifest);
    if (!androidManifestXml.isFile()) {
      throw new HumanReadableException(
          "Manifest file %s does not exist, so could not extract package name.",
          pathToManifest);
    }

    try {
      return DefaultAndroidManifestReader.forPath(pathToManifest).getPackage();
    } catch (IOException e) {
      throw new HumanReadableException("Could not extract package name from %s", pathToManifest);
    }
  }

}
