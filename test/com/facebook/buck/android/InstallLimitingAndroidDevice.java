/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.exopackage.DexExoHelper;
import com.facebook.buck.android.exopackage.NativeExoHelper;
import com.facebook.buck.android.exopackage.ResourcesExoHelper;
import com.facebook.buck.android.exopackage.TestAndroidDevice;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/** This allows limiting the number of apks/dexes/etc that are installed to a device. Useful */
class InstallLimitingAndroidDevice extends DelegatingAndroidDevice {
  // Per install state.
  private int allowedInstalledApks;
  private int allowedInstalledDexes;
  private int allowedInstalledLibs;
  private int allowedInstalledResources;

  private final Path apkPath;
  private final Path agentApkPath;
  private final Path installRoot;

  InstallLimitingAndroidDevice(
      TestAndroidDevice device, Path installRoot, Path apkPath, Path agentApkPath) {
    super(device);
    this.installRoot = installRoot;
    this.apkPath = apkPath;
    this.agentApkPath = agentApkPath;

    allowedInstalledApks = 0;
    allowedInstalledDexes = 0;
    allowedInstalledLibs = 0;
    allowedInstalledResources = 0;
  }

  @Override
  public boolean installApkOnDevice(
      File apk, boolean installViaSd, boolean quiet, boolean verifyTempWritable) {
    if (apk.equals(apkPath.toFile())) {
      allowedInstalledApks--;
      assertTrue(allowedInstalledApks >= 0);
    } else {
      if (!apk.equals(agentApkPath.toFile())) {
        throw new UnsupportedOperationException("apk path=" + apk);
      }
    }
    return super.installApkOnDevice(apk, installViaSd, quiet, verifyTempWritable);
  }

  @Override
  public void installFile(Path targetDevicePath, Path source) throws Exception {
    assertTrue(
        String.format(
            "Exopackage should only install files to the install root (%s, %s)",
            installRoot, targetDevicePath),
        targetDevicePath.startsWith(installRoot));

    Path relativePath = installRoot.relativize(targetDevicePath);
    if (relativePath.startsWith(DexExoHelper.SECONDARY_DEX_DIR)) {
      if (!relativePath.getFileName().equals(Paths.get("metadata.txt"))) {
        allowedInstalledDexes--;
        assertTrue(allowedInstalledDexes >= 0);
      }
    } else if (relativePath.startsWith(NativeExoHelper.NATIVE_LIBS_DIR)) {
      if (!relativePath.getFileName().equals(Paths.get("metadata.txt"))) {
        allowedInstalledLibs--;
        assertTrue(allowedInstalledLibs >= 0);
      }
    } else if (relativePath.startsWith(ResourcesExoHelper.RESOURCES_DIR)) {
      if (!relativePath.getFileName().equals(Paths.get("metadata.txt"))) {
        allowedInstalledResources--;
        assertTrue(allowedInstalledResources >= 0);
      }
    } else {
      fail("Unrecognized target path (" + relativePath + ")");
    }
    super.installFile(targetDevicePath, source);
  }

  public void setAllowedInstallCounts(
      int expectedApksInstalled,
      int expectedDexesInstalled,
      int expectedLibsInstalled,
      int expectedResourcesInstalled) {
    this.allowedInstalledApks = expectedApksInstalled;
    this.allowedInstalledDexes = expectedDexesInstalled;
    this.allowedInstalledLibs = expectedLibsInstalled;
    this.allowedInstalledResources = expectedResourcesInstalled;
  }

  public void assertExpectedInstallsAreConsumed() {
    assertEquals("apk should be installed but wasn't", 0, allowedInstalledApks);
    assertEquals("fewer dexes installed than expected", 0, allowedInstalledDexes);
    assertEquals("fewer libs installed than expected", 0, allowedInstalledLibs);
    assertEquals("fewer resources installed than expected", 0, allowedInstalledResources);
  }
}
