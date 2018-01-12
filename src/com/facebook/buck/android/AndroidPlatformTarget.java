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

package com.facebook.buck.android;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.List;

/**
 * Represents a platform to target for Android. Eventually, it should be possible to construct an
 * arbitrary platform target, but currently, we only recognize a fixed set of targets.
 */
public class AndroidPlatformTarget {

  public static final String DEFAULT_ANDROID_PLATFORM_TARGET = "android-23";

  private final String name;
  private final Path androidJar;
  private final List<Path> bootclasspathEntries;
  private final Path aaptExecutable;
  private final Path aapt2Executable;
  private final Path adbExecutable;
  private final Path aidlExecutable;
  private final Path zipalignExecutable;
  private final Path dxExecutable;
  private final Path androidFrameworkIdlFile;
  private final Path proguardJar;
  private final Path proguardConfig;
  private final Path optimizedProguardConfig;

  public AndroidPlatformTarget(
      String name,
      Path androidJar,
      List<Path> bootclasspathEntries,
      Path aaptExecutable,
      Path aapt2Executable,
      Path adbExecutable,
      Path aidlExecutable,
      Path zipalignExecutable,
      Path dxExecutable,
      Path androidFrameworkIdlFile,
      Path proguardJar,
      Path proguardConfig,
      Path optimizedProguardConfig) {
    this.name = name;
    this.androidJar = androidJar;
    this.bootclasspathEntries = ImmutableList.copyOf(bootclasspathEntries);
    this.aaptExecutable = aaptExecutable;
    this.aapt2Executable = aapt2Executable;
    this.adbExecutable = adbExecutable;
    this.aidlExecutable = aidlExecutable;
    this.zipalignExecutable = zipalignExecutable;
    this.dxExecutable = dxExecutable;
    this.androidFrameworkIdlFile = androidFrameworkIdlFile;
    this.proguardJar = proguardJar;
    this.proguardConfig = proguardConfig;
    this.optimizedProguardConfig = optimizedProguardConfig;
  }

  /** This is likely something like {@code "Google Inc.:Google APIs:21"}. */
  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return getName();
  }

  public Path getAndroidJar() {
    return androidJar;
  }

  /** @return bootclasspath entries as absolute {@link Path}s */
  public List<Path> getBootclasspathEntries() {
    return bootclasspathEntries;
  }

  public Path getAaptExecutable() {
    return aaptExecutable;
  }

  public Path getAapt2Executable() {
    return aapt2Executable;
  }

  public Path getAdbExecutable() {
    return adbExecutable;
  }

  public Path getAidlExecutable() {
    return aidlExecutable;
  }

  public Path getZipalignExecutable() {
    return zipalignExecutable;
  }

  public Path getDxExecutable() {
    return dxExecutable;
  }

  public Path getAndroidFrameworkIdlFile() {
    return androidFrameworkIdlFile;
  }

  public Path getProguardJar() {
    return proguardJar;
  }

  public Path getProguardConfig() {
    return proguardConfig;
  }

  public Path getOptimizedProguardConfig() {
    return optimizedProguardConfig;
  }
}
