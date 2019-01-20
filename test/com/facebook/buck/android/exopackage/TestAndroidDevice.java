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

import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * This simulates the state of a real device enough that we can verify that exo installation happens
 * correctly.
 */
public class TestAndroidDevice implements AndroidDevice {
  public static final String PACKAGE_INFO_PATH = "package.info";
  private static final Path APK_INSTALL_DIR = Paths.get("/data/app/");
  public static final Path APK_FILE_NAME = Paths.get("base.apk");
  public final String abi;

  // Directory that holds persistent "device" state.
  private final Path stateDirectory;
  private final String serial;
  private final ApkInfoReader apkInfoReader;

  public Map<String, Path> getInstalledApks() throws Exception {
    return listDirRecursive(APK_INSTALL_DIR)
        .stream()
        .filter(p -> p.getFileName().equals(APK_FILE_NAME))
        .collect(
            ImmutableMap.toImmutableMap(
                p -> p.getParent().getFileName().toString(),
                p -> resolve(APK_INSTALL_DIR.resolve(p))));
  }

  public Map<Path, Path> getInstalledFiles() throws Exception {
    return Files.walk(stateDirectory)
        .filter(s -> s.toFile().isFile())
        .filter(p -> !p.startsWith(resolve(APK_INSTALL_DIR)))
        .collect(ImmutableMap.toImmutableMap(this::toDevicePath, p -> p));
  }

  public interface ApkInfoReader {
    ApkInfo read(File apk);
  }

  public static class ApkInfo {
    final String packageName;
    final String versionCode;

    public ApkInfo(String packageName, String versionCode) {
      this.packageName = packageName;
      this.versionCode = versionCode;
    }
  }

  public TestAndroidDevice(
      ApkInfoReader apkInfoReader, Path stateDirectory, String serial, String abi) {
    this.abi = abi;
    this.stateDirectory = stateDirectory;
    this.serial = serial;
    this.apkInfoReader = apkInfoReader;
  }

  @Override
  public boolean installApkOnDevice(
      File apk, boolean installViaSd, boolean quiet, boolean verifyTempWritable) {
    assertTrue(apk.isAbsolute());
    try {
      ApkInfo apkInfo = apkInfoReader.read(apk);
      Optional<PackageInfo> previousInfo = getPackageInfo(apkInfo.packageName);
      previousInfo.ifPresent(
          info -> {
            if (info.versionCode.compareTo(apkInfo.versionCode) > 0) {
              throw new RuntimeException();
            }
          });
      Files.createDirectories(getPackageDirectory(apkInfo.packageName));
      writePackageInfo(apkInfo);
      Files.copy(
          apk.toPath(), getApkPath(apkInfo.packageName), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return true;
  }

  private void writePackageInfo(ApkInfo apkInfo) throws IOException {
    // apk path, native lib path, version code
    Path packageDirectory = getPackageDirectory(apkInfo.packageName);
    Path packageInfoPath = packageDirectory.resolve(PACKAGE_INFO_PATH);
    Path apkPath = getApkPath(apkInfo.packageName);
    Files.write(
        packageInfoPath,
        ImmutableList.of(
            toDevicePath(apkPath).toString(),
            toDevicePath(packageDirectory.resolve("libs")).toString(),
            apkInfo.versionCode));
  }

  private Path toDevicePath(Path packageDirectory) {
    return Paths.get("/").resolve(stateDirectory.relativize(packageDirectory));
  }

  private Path getApkPath(String packageName) {
    return getPackageDirectory(packageName).resolve(APK_FILE_NAME);
  }

  private Path getPackageDirectory(String packageName) {
    return resolve(APK_INSTALL_DIR.resolve(packageName));
  }

  @Override
  public void stopPackage(String packageName) {
    // noop
  }

  @Override
  public Optional<PackageInfo> getPackageInfo(String packageName) throws IOException {
    Path packageInfoPath = getPackageDirectory(packageName).resolve(PACKAGE_INFO_PATH);
    if (packageInfoPath.toFile().exists()) {
      List<String> lines = Files.readAllLines(packageInfoPath);
      Preconditions.checkState(lines.size() == 3);
      return Optional.of(new PackageInfo(lines.get(0), lines.get(1), lines.get(2)));
    } else {
      return Optional.empty();
    }
  }

  @Override
  public void uninstallPackage(String packageName) {
    try {
      MostFiles.deleteRecursively(getPackageDirectory(packageName));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getSignature(String packagePath) throws Exception {
    Path apkPath = resolve(packagePath);
    if (apkPath.toFile().exists()) {
      return AgentUtil.getJarSignature(apkPath.toString());
    }
    return "";
  }

  @Override
  public ImmutableSortedSet<Path> listDirRecursive(Path dirPath) throws Exception {
    Path devicePath = resolve(dirPath);
    return Files.walk(devicePath)
        .filter(s -> s.toFile().isFile())
        .map(devicePath::relativize)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  @Override
  public void rmFiles(String dirPath, Iterable<String> filesToDelete) {
    for (String s : filesToDelete) {
      try {
        Files.delete(resolve(dirPath).resolve(s));
      } catch (IOException e) {
        throw new BuckUncheckedExecutionException(e);
      }
    }
  }

  @Override
  public AutoCloseable createForward() {
    // TODO(cjhopman): track correct forwarding usage
    return () -> {};
  }

  @Override
  public void installFiles(String filesType, Map<Path, Path> installPaths) throws Exception {
    for (Map.Entry<Path, Path> entry : installPaths.entrySet()) {
      Path targetDevicePath = entry.getKey();
      Path source = entry.getValue();
      // TODO(cjhopman): verify port and agentCommand
      assertTrue(targetDevicePath.isAbsolute());
      assertTrue(source.isAbsolute());
      Path targetPath = resolve(targetDevicePath);
      assertTrue(targetPath.getParent().toFile().exists());
      Files.copy(source, targetPath);
    }
  }

  @Override
  public void mkDirP(String dir) throws Exception {
    Files.createDirectories(resolve(dir));
  }

  @Override
  public String getProperty(String name) {
    switch (name) {
      case "ro.build.version.sdk":
        return "20";
    }
    throw new UnsupportedOperationException("Tried to get prop " + name);
  }

  @Override
  public List<String> getDeviceAbis() {
    return ImmutableList.of(abi);
  }

  @Override
  public void killProcess(String packageName) {
    // noop
  }

  @Override
  public void sendBroadcast(String action, Map<String, String> stringExtras) {
    // noop
  }

  @Override
  public String getSerialNumber() {
    return serial;
  }

  private Path resolve(String path) {
    return resolve(Paths.get(path));
  }

  private Path resolve(Path path) {
    Preconditions.checkArgument(path.isAbsolute());
    return stateDirectory.resolve(path.getRoot().relativize(path));
  }
}
