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
package com.facebook.buck.android;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;
import java.util.Objects;

public class FakeAndroidDirectoryResolver implements AndroidDirectoryResolver {
  private final Optional<Path> androidSdkDir;
  private final Optional<Path> androidNdkDir;
  private final Optional<String> ndkVersion;

  public FakeAndroidDirectoryResolver() {
    this(
        /* androidSdkDir */ Optional.<Path>absent(),
        /* androidNdkDir */ Optional.<Path>absent(),
        /* ndkVersion */ Optional.<String>absent());
  }

  public FakeAndroidDirectoryResolver(
      Optional<Path> androidSdkDir,
      Optional<Path> androidNdkDir,
      Optional<String> ndkVersion) {
    this.androidSdkDir = Preconditions.checkNotNull(androidSdkDir);
    this.androidNdkDir = Preconditions.checkNotNull(androidNdkDir);
    this.ndkVersion = Preconditions.checkNotNull(ndkVersion);
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
  public Optional<String> getNdkVersion() {
    return ndkVersion;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FakeAndroidDirectoryResolver)) {
      return false;
    }

    FakeAndroidDirectoryResolver that = (FakeAndroidDirectoryResolver) o;

    return
        Objects.equals(androidNdkDir, that.androidNdkDir) &&
        Objects.equals(androidSdkDir, that.androidSdkDir) &&
        Objects.equals(ndkVersion, that.ndkVersion);
  }

  @Override
  public int hashCode() {
    return Objects.hash(androidNdkDir, androidSdkDir, ndkVersion);
  }

}
