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
import com.facebook.buck.android.exopackage.ModuleExoHelper;
import com.facebook.buck.android.exopackage.NativeExoHelper;
import com.facebook.buck.android.exopackage.ResourcesExoHelper;
import com.facebook.buck.android.exopackage.TestAndroidDevice;
import com.google.common.base.Joiner;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** This allows limiting the number of apks/dexes/etc that are installed to a device. Useful */
class InstallLimitingAndroidDevice extends DelegatingAndroidDevice {
  // Per install state.
  private int allowedInstalledApks;
  private int allowedInstalledDexes;
  private int allowedInstalledLibs;
  private int allowedInstalledResources;
  private int allowedInstalledModules;

  private List<Path> installedApks;
  private List<Path> installedDexes;
  private List<Path> installedLibs;
  private List<Path> installedResources;
  private List<Path> installedModules;

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
    allowedInstalledModules = 0;

    installedApks = new ArrayList<>();
    installedDexes = new ArrayList<>();
    installedLibs = new ArrayList<>();
    installedResources = new ArrayList<>();
    installedModules = new ArrayList<>();
  }

  @Override
  public boolean installApkOnDevice(
      File apk, boolean installViaSd, boolean quiet, boolean verifyTempWritable) {
    if (apk.equals(apkPath.toFile())) {
      installedApks.add(apk.toPath());
      allowedInstalledApks--;
      assertTrue(
          String.format("Installed paths: [%s]", Joiner.on(", ").join(installedApks)),
          allowedInstalledApks >= 0);
    } else {
      if (!apk.equals(agentApkPath.toFile())) {
        throw new UnsupportedOperationException("apk path=" + apk);
      }
    }
    return super.installApkOnDevice(apk, installViaSd, quiet, verifyTempWritable);
  }

  private void validateInstallFile(Path targetDevicePath, Path source) {
    assertTrue(
        String.format(
            "Exopackage should only install files to the install root (%s, %s)",
            installRoot, targetDevicePath),
        targetDevicePath.startsWith(installRoot));

    Path relativePath = installRoot.relativize(targetDevicePath);

    if (relativePath.startsWith(DexExoHelper.SECONDARY_DEX_DIR)) {
      installedDexes.add(source);
      if (!relativePath.getFileName().equals(Paths.get("metadata.txt"))) {
        allowedInstalledDexes--;
        assertTrue(
            String.format("Installed paths: [%s]", Joiner.on(", ").join(installedDexes)),
            allowedInstalledDexes >= 0);
      }
    } else if (relativePath.startsWith(NativeExoHelper.NATIVE_LIBS_DIR)) {
      installedLibs.add(source);
      if (!relativePath.getFileName().equals(Paths.get("metadata.txt"))) {
        allowedInstalledLibs--;
        assertTrue(
            String.format("Installed paths: [%s]", Joiner.on(", ").join(installedLibs)),
            allowedInstalledLibs >= 0);
      }
    } else if (relativePath.startsWith(ResourcesExoHelper.RESOURCES_DIR)) {
      installedResources.add(source);
      if (!relativePath.getFileName().equals(Paths.get("metadata.txt"))) {
        allowedInstalledResources--;
        assertTrue(
            String.format("Installed paths: [%s]", Joiner.on(", ").join(installedResources)),
            allowedInstalledResources >= 0);
      }
    } else if (relativePath.startsWith(ModuleExoHelper.MODULAR_DEX_DIR)) {
      installedModules.add(source);
      Path fileName = relativePath.getFileName();
      if (!fileName.equals(Paths.get("metadata.txt"))
          && !fileName.toString().endsWith(".metadata")) {
        allowedInstalledModules--;
        assertTrue(
            String.format("Installed paths: [%s]", Joiner.on(", ").join(installedModules)),
            allowedInstalledModules >= 0);
      }
    } else {
      fail("Unrecognized target path (" + relativePath + ")");
    }
  }

  @Override
  public void installFiles(String filesType, Map<Path, Path> installPaths) throws Exception {
    for (Map.Entry<Path, Path> entry : installPaths.entrySet()) {
      validateInstallFile(entry.getKey(), entry.getValue());
    }
    super.installFiles(filesType, installPaths);
  }

  public void setAllowedInstallCounts(
      int expectedApksInstalled,
      int expectedDexesInstalled,
      int expectedLibsInstalled,
      int expectedResourcesInstalled,
      int expectedModulesInstalled) {
    this.allowedInstalledApks = expectedApksInstalled;
    this.allowedInstalledDexes = expectedDexesInstalled;
    this.allowedInstalledLibs = expectedLibsInstalled;
    this.allowedInstalledResources = expectedResourcesInstalled;
    this.allowedInstalledModules = expectedModulesInstalled;

    installedApks.clear();
    installedDexes.clear();
    installedLibs.clear();
    installedResources.clear();
    installedModules.clear();
  }

  public void assertExpectedInstallsAreConsumed() {
    assertEquals("apk should be installed but wasn't", 0, allowedInstalledApks);
    assertEquals("fewer dexes installed than expected", 0, allowedInstalledDexes);
    assertEquals("fewer libs installed than expected", 0, allowedInstalledLibs);
    assertEquals("fewer resources installed than expected", 0, allowedInstalledResources);
    assertEquals("fewer modules installed than expected", 0, allowedInstalledModules);
  }
}
