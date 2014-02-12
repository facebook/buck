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

import com.android.ddmlib.AndroidDebugBridge;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.util.DefaultAndroidManifestReader;
import com.facebook.buck.util.HumanReadableException;

import java.io.File;
import java.io.IOException;

/**
 * Base class for Commands which need {@link AndroidDebugBridge} and also need to
 * uninstall packages.
 */
public abstract class UninstallSupportCommandRunner<T extends AbstractCommandOptions>
    extends AbstractCommandRunner<T> {

  protected final AdbHelper adbHelper;

  protected UninstallSupportCommandRunner(CommandRunnerParams params) {
    super(params);
    adbHelper = new AdbHelper(console, getBuckEventBus());
  }

  public static String tryToExtractPackageNameFromManifest(InstallableApk androidBinaryRule) {
    String pathToManifest = androidBinaryRule.getManifestPath().toString();

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
