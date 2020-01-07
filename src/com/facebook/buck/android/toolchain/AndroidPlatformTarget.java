/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableCollection;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.immutables.value.Value;

/**
 * Represents a platform to target for Android. Eventually, it should be possible to construct an
 * arbitrary platform target, but currently, we only recognize a fixed set of targets.
 */
@BuckStyleValue
public abstract class AndroidPlatformTarget implements Toolchain, AddsToRuleKey {
  public static final String DEFAULT_NAME = "android-platform-target";

  public static final String DEFAULT_ANDROID_PLATFORM_TARGET = "android-23";

  @Override
  public String getName() {
    return DEFAULT_NAME;
  }

  /** This is likely something like {@code "Google Inc.:Google APIs:21"}. */
  @AddToRuleKey
  public abstract String getPlatformName();

  @Override
  public String toString() {
    return getPlatformName();
  }

  public abstract Path getAndroidJar();

  /** @return bootclasspath entries as absolute {@link Path}s */
  public abstract List<Path> getBootclasspathEntries();

  public abstract Supplier<Tool> getAaptExecutable();

  public abstract ToolProvider getAapt2ToolProvider();

  public abstract Path getAdbExecutable();

  public abstract Path getAidlExecutable();

  public abstract Path getZipalignExecutable();

  public abstract Path getDxExecutable();

  public abstract Path getAndroidFrameworkIdlFile();

  public abstract Path getProguardJar();

  public abstract Path getProguardConfig();

  public abstract Path getOptimizedProguardConfig();

  /** Process aapt2 tool's parse dependencies and adds them to the {@code builder} */
  @Value.Derived
  public void addParseTimeDeps(
      ImmutableCollection.Builder<BuildTarget> builder, TargetConfiguration targetConfiguration) {
    builder.addAll(getAapt2ToolProvider().getParseTimeDeps(targetConfiguration));
  }

  public static AndroidPlatformTarget of(
      String platformName,
      Path androidJar,
      List<Path> bootclasspathEntries,
      Supplier<Tool> aaptExecutable,
      ToolProvider aapt2ToolProvider,
      Path adbExecutable,
      Path aidlExecutable,
      Path zipalignExecutable,
      Path dxExecutable,
      Path androidFrameworkIdlFile,
      Path proguardJar,
      Path proguardConfig,
      Path optimizedProguardConfig) {
    return ImmutableAndroidPlatformTarget.of(
        platformName,
        androidJar,
        bootclasspathEntries,
        aaptExecutable,
        aapt2ToolProvider,
        adbExecutable,
        aidlExecutable,
        zipalignExecutable,
        dxExecutable,
        androidFrameworkIdlFile,
        proguardJar,
        proguardConfig,
        optimizedProguardConfig);
  }
}
