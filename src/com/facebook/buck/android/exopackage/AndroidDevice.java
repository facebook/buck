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

import com.android.ddmlib.InstallException;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AndroidDevice {
  default boolean installApkOnDevice(File apk, boolean installViaSd, boolean quiet) {
    return installApkOnDevice(apk, installViaSd, quiet, true);
  }

  boolean installApkOnDevice(
      File apk, boolean installViaSd, boolean quiet, boolean verifyTempWritable);

  void stopPackage(String packageName) throws Exception;

  Optional<PackageInfo> getPackageInfo(String packageName) throws Exception;

  void uninstallPackage(String packageName) throws InstallException;

  String getSignature(String packagePath) throws Exception;

  ImmutableSortedSet<Path> listDirRecursive(Path dirPath) throws Exception;

  void rmFiles(String dirPath, Iterable<String> filesToDelete);

  AutoCloseable createForward() throws Exception;

  void installFiles(String filesType, Map<Path, Path> installPaths) throws Exception;

  void mkDirP(String dirpath) throws Exception;

  String getProperty(String name) throws Exception;

  List<String> getDeviceAbis() throws Exception;

  void killProcess(String processName) throws Exception;

  void sendBroadcast(String action, Map<String, String> stringExtras) throws Exception;

  String getSerialNumber();
}
