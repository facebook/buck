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

import com.android.ddmlib.InstallException;
import com.facebook.buck.android.exopackage.AndroidDevice;
import com.facebook.buck.android.exopackage.PackageInfo;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DelegatingAndroidDevice implements AndroidDevice {
  private final AndroidDevice delegate;

  public DelegatingAndroidDevice(AndroidDevice delegate) {
    this.delegate = delegate;
  }

  @Override
  public boolean installApkOnDevice(
      File apk, boolean installViaSd, boolean quiet, boolean verifyTempWritable) {
    return delegate.installApkOnDevice(apk, installViaSd, quiet, verifyTempWritable);
  }

  @Override
  public void stopPackage(String packageName) throws Exception {
    delegate.stopPackage(packageName);
  }

  @Override
  public Optional<PackageInfo> getPackageInfo(String packageName) throws Exception {
    return delegate.getPackageInfo(packageName);
  }

  @Override
  public void uninstallPackage(String packageName) throws InstallException {
    delegate.uninstallPackage(packageName);
  }

  @Override
  public String getSignature(String packagePath) throws Exception {
    return delegate.getSignature(packagePath);
  }

  @Override
  public ImmutableSortedSet<Path> listDirRecursive(Path dirPath) throws Exception {
    return delegate.listDirRecursive(dirPath);
  }

  @Override
  public void rmFiles(String dirPath, Iterable<String> filesToDelete) {
    delegate.rmFiles(dirPath, filesToDelete);
  }

  @Override
  public AutoCloseable createForward() throws Exception {
    return delegate.createForward();
  }

  @Override
  public void installFiles(String filesType, Map<Path, Path> installPaths) throws Exception {
    delegate.installFiles(filesType, installPaths);
  }

  @Override
  public void mkDirP(String dirpath) throws Exception {
    delegate.mkDirP(dirpath);
  }

  @Override
  public String getProperty(String name) throws Exception {
    return delegate.getProperty(name);
  }

  @Override
  public List<String> getDeviceAbis() throws Exception {
    return delegate.getDeviceAbis();
  }

  @Override
  public void killProcess(String processName) throws Exception {
    delegate.killProcess(processName);
  }

  @Override
  public void sendBroadcast(String action, Map<String, String> stringExtras) throws Exception {
    delegate.sendBroadcast(action, stringExtras);
  }

  @Override
  public String getSerialNumber() {
    return delegate.getSerialNumber();
  }
}
