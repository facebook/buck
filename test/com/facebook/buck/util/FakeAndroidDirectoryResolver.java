/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.util;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

public class FakeAndroidDirectoryResolver implements AndroidDirectoryResolver {
  private final Optional<Path> androidSdkDir;
  private final Optional<Path> androidNdkDir;
  private final String ndkVersion;

  public FakeAndroidDirectoryResolver() {
    this(
        /* androidSdkVersion */ Optional.<Path>absent(),
        /* androidNdkVersion */ Optional.<Path>absent(),
        /* ndkVersion */ "");
  }

  public FakeAndroidDirectoryResolver(
      Optional<Path> androidSdkDir,
      Optional<Path> androidNdkDir,
      String ndkVersion) {
    this.androidSdkDir = androidSdkDir;
    this.androidNdkDir = androidNdkDir;
    this.ndkVersion = ndkVersion;
  }

  @Override
  public Optional<Path> findAndroidSdkDirSafe() {
    return androidSdkDir;
  }

  @Override
  public Path findAndroidSdkDir() {
    Preconditions.checkState(androidSdkDir.isPresent());
    return androidSdkDir.get();
  }

  @Override
  public Optional<Path> findAndroidNdkDir() {
    return androidNdkDir;
  }

  @Override
  public String getNdkVersion(Path ndkPath) {
    return ndkVersion;
  }
}
