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

package com.facebook.buck.android.toolchain;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.toolchain.Toolchain;
import java.nio.file.Path;
import java.util.List;
import org.immutables.value.Value;

/**
 * Represents a platform to target for Android. Eventually, it should be possible to construct an
 * arbitrary platform target, but currently, we only recognize a fixed set of targets.
 */
@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
public abstract class AbstractAndroidPlatformTarget implements Toolchain {

  public static final String DEFAULT_NAME = "android-platform-target";

  public static final String DEFAULT_ANDROID_PLATFORM_TARGET = "android-23";

  /** This is likely something like {@code "Google Inc.:Google APIs:21"}. */
  @Value.Parameter
  public abstract String getName();

  @Override
  public String toString() {
    return getName();
  }

  @Value.Parameter
  public abstract Path getAndroidJar();

  /** @return bootclasspath entries as absolute {@link Path}s */
  @Value.Parameter
  public abstract List<Path> getBootclasspathEntries();

  @Value.Parameter
  public abstract Path getAaptExecutable();

  @Value.Parameter
  public abstract Path getAapt2Executable();

  @Value.Parameter
  public abstract Path getAdbExecutable();

  @Value.Parameter
  public abstract Path getAidlExecutable();

  @Value.Parameter
  public abstract Path getZipalignExecutable();

  @Value.Parameter
  public abstract Path getDxExecutable();

  @Value.Parameter
  public abstract Path getAndroidFrameworkIdlFile();

  @Value.Parameter
  public abstract Path getProguardJar();

  @Value.Parameter
  public abstract Path getProguardConfig();

  @Value.Parameter
  public abstract Path getOptimizedProguardConfig();
}
