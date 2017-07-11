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

package com.facebook.buck.android.exopackage;

import static org.junit.Assert.assertTrue;

import com.android.ddmlib.InstallException;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * This simulates the state of a real device enough that we can verify that exo installation happens
 * correctly.
 */
public class TestAndroidDevice implements AndroidDevice {
  private final String abi;
  // Persistent "device" state.
  private final NavigableMap<String, String> deviceState;
  private final Set<Path> directories;
  private final Path apkPath;
  private final Path agentApkPath;
  private final Path installRoot;
  private final String apkVersionCode;
  private final Path apkDevicePath;
  private final ProjectFilesystem filesystem;
  private final String apkPackageName;

  private Optional<PackageInfo> deviceAgentPackageInfo;
  private Optional<PackageInfo> fakePackageInfo;
  private String packageSignature;

  public TestAndroidDevice(
      String abi,
      Path apkPath,
      Path agentApkPath,
      Path installRoot,
      String apkVersionCode,
      Path apkDevicePath,
      ProjectFilesystem filesystem,
      String apkPackageName) {
    this.abi = abi;
    this.apkPath = apkPath;
    this.agentApkPath = agentApkPath;
    this.installRoot = installRoot;
    this.apkVersionCode = apkVersionCode;
    this.apkDevicePath = apkDevicePath;
    this.filesystem = filesystem;
    this.apkPackageName = apkPackageName;
    this.deviceState = new TreeMap<>();
    this.directories = new HashSet<>();
    this.deviceAgentPackageInfo = Optional.empty();
    this.fakePackageInfo = Optional.empty();
  }

  @Override
  public boolean installApkOnDevice(
      File apk, boolean installViaSd, boolean quiet, boolean verifyTempWritable) {
    assertTrue(apk.isAbsolute());
    if (apk.equals(agentApkPath.toFile())) {
      deviceAgentPackageInfo =
          Optional.of(
              new PackageInfo(
                  "/data/app/Agent.apk", "/data/data/whatever", AgentUtil.AGENT_VERSION_CODE));
      return true;
    } else if (apk.equals(apkPath.toFile())) {
      fakePackageInfo =
          Optional.of(
              new PackageInfo(
                  apkDevicePath.toString(), "/data/data/whatever_else", apkVersionCode));
      try {
        deviceState.put(apkDevicePath.toString(), filesystem.computeSha1(apkPath).toString());
        packageSignature = AgentUtil.getJarSignature(apk.toString());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return true;
    }
    throw new UnsupportedOperationException("apk path=" + apk);
  }

  @Override
  public void stopPackage(String packageName) throws Exception {
    // noop
  }

  @Override
  public Optional<PackageInfo> getPackageInfo(String packageName) throws Exception {
    if (packageName.equals(AgentUtil.AGENT_PACKAGE_NAME)) {
      return deviceAgentPackageInfo;
    } else if (packageName.equals(apkPackageName)) {
      return fakePackageInfo;
    }
    throw new UnsupportedOperationException("Tried to get package info " + packageName);
  }

  @Override
  public void uninstallPackage(String packageName) throws InstallException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getSignature(String packagePath) throws Exception {
    assertTrue(deviceState.containsKey(packagePath));
    return packageSignature;
  }

  @Override
  public ImmutableSortedSet<Path> listDirRecursive(Path dirPath) throws Exception {
    String dir = dirPath.toString();
    return deviceState
        .subMap(dir, false, dir + new String(Character.toChars(255)), false)
        .keySet()
        .stream()
        .map(f -> dirPath.relativize(Paths.get(f)))
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  @Override
  public void rmFiles(String dirPath, Iterable<String> filesToDelete) throws Exception {
    for (String s : filesToDelete) {
      deviceState.remove(dirPath + "/" + s);
    }
  }

  @Override
  public AutoCloseable createForward() throws Exception {
    // TODO(cjhopman): track correct forwarding usage
    return () -> {};
  }

  @Override
  public void installFile(Path targetDevicePath, Path source) throws Exception {
    // TODO(cjhopman): verify port and agentCommand
    assertTrue(targetDevicePath.isAbsolute());
    assertTrue(source.isAbsolute());
    assertTrue(
        String.format(
            "Exopackage should only install files to the install root (%s, %s)",
            installRoot, targetDevicePath),
        targetDevicePath.startsWith(installRoot));
    MoreAsserts.assertContainsOne(directories, targetDevicePath.getParent());
    deviceState.put(targetDevicePath.toString(), filesystem.readFileIfItExists(source).get());
  }

  @Override
  public void mkDirP(String dir) throws Exception {
    Path dirPath = Paths.get(dir);
    while (dirPath != null) {
      directories.add(dirPath);
      dirPath = dirPath.getParent();
    }
  }

  @Override
  public String getProperty(String name) throws Exception {
    switch (name) {
      case "ro.build.version.sdk":
        return "20";
    }
    throw new UnsupportedOperationException("Tried to get prop " + name);
  }

  @Override
  public List<String> getDeviceAbis() throws Exception {
    return ImmutableList.of(abi);
  }

  @Override
  public void killProcess(String packageName) throws Exception {
    // noop
  }

  @Override
  public String getSerialNumber() {
    return "fake.serial";
  }

  public NavigableMap<String, String> getDeviceState() {
    return deviceState;
  }
}
