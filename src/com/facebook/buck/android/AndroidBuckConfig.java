/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.android.toolchain.ndk.NdkCompilerType;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntimeType;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.tool.config.ToolConfig;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;

public class AndroidBuckConfig {

  private static final String CONFIG_ENTRY_IN_SDK_PATH_SEARCH_ORDER = "<CONFIG>";

  /** Values acceptable for ndk.ndk_search_order. */
  public enum NdkSearchOrderEntry {
    ANDROID_NDK_REPOSITORY_ENV("ANDROID_NDK_REPOSITORY"),
    ANDROID_NDK_ENV("ANDROID_NDK"),
    NDK_HOME_ENV("NDK_HOME"),
    ANDROID_NDK_HOME_ENV("ANDROID_NDK_HOME"),
    NDK_REPOSITORY_CONFIG("<NDK_REPOSITORY_CONFIG>"),
    NDK_DIRECTORY_CONFIG("<NDK_DIRECTORY_CONFIG>"),
    ;
    public final String entryValue;

    NdkSearchOrderEntry(String entryValue) {
      this.entryValue = entryValue;
    }
  }

  private static final ImmutableList<String> DEFAULT_SDK_PATH_SEARCH_ORDER =
      ImmutableList.of("ANDROID_SDK", "ANDROID_HOME", CONFIG_ENTRY_IN_SDK_PATH_SEARCH_ORDER);
  private static final ImmutableList<NdkSearchOrderEntry> DEFAULT_NDK_SEARCH_ORDER =
      ImmutableList.of(
          NdkSearchOrderEntry.ANDROID_NDK_REPOSITORY_ENV,
          NdkSearchOrderEntry.ANDROID_NDK_ENV,
          NdkSearchOrderEntry.NDK_HOME_ENV,
          NdkSearchOrderEntry.NDK_REPOSITORY_CONFIG,
          NdkSearchOrderEntry.NDK_DIRECTORY_CONFIG);

  private static final String ANDROID_SECTION = "android";
  private static final String REDEX = "redex";

  public static final ImmutableSet<String> VALID_ABI_KEYS =
      ImmutableSet.of("arm", "armv7", "arm64", "x86", "x86_64");

  private final BuckConfig delegate;
  private final Platform platform;

  public AndroidBuckConfig(BuckConfig delegate, Platform platform) {
    this.delegate = delegate;
    this.platform = platform;
  }

  public Optional<String> getAndroidTarget() {
    return delegate.getValue("android", "target");
  }

  public Optional<String> getBuildToolsVersion() {
    return delegate.getValue("android", "build_tools_version");
  }

  public Integer getAdbTimeout() {
    return delegate.getInteger("android", "adb_timeout").orElse(60000);
  }

  public Optional<String> getSdkPath() {
    return delegate.getValue("android", "sdk_path");
  }

  /**
   * Defines the order of search of the path to Android SDK.
   *
   * <p>The order is the list of elements that can either be {@code <CONFIG>} (to indicate the entry
   * from {@code .buckconfig}) or the name of an environment variable that contains path to Android
   * SDK (for example, {@code ANDROID_SDK}).
   *
   * <p>If nothing is specified in {@code .buckconfig} the default order is: {@code ANDROID_SDK},
   * {@code ANDROID_HOME}, {@code <CONFIG>}
   */
  public ImmutableList<String> getSdkPathSearchOrder() {
    return delegate
        .getOptionalListWithoutComments("android", "sdk_path_search_order")
        .orElse(DEFAULT_SDK_PATH_SEARCH_ORDER);
  }

  /**
   * Given the entry to from the order of search of the Android SDK location returns the name of the
   * configuration option that contains SDK path if the entry instructs to get that value from
   * {@code .buckconfig} (i.e. it's {@code <CONFIG>}) or {@code Optional.empty()} in other cases.
   */
  public Optional<String> getSdkPathConfigOptionFromSearchOrderEntry(String entry) {
    if (CONFIG_ENTRY_IN_SDK_PATH_SEARCH_ORDER.equals(entry)) {
      return Optional.of("android.sdk_path");
    } else {
      return Optional.empty();
    }
  }

  public Optional<String> getNdkVersion() {
    return delegate.getValue("ndk", "ndk_version");
  }

  public Optional<String> getNdkPath() {
    return delegate.getValue("ndk", "ndk_path");
  }

  public Optional<String> getNdkRepositoryPath() {
    return delegate.getValue("ndk", "ndk_repository_path");
  }

  /**
   * Defines the order of search of the Android NDK.
   *
   * <p>The order is the list of elements that can either be {@code <NDK_REPOSITORY_CONFIG>} to
   * indicate the entry {@code ndk.ndk_repo_path} from {@code .buckconfig}, {@code
   * <NDK_DIRECTORY_CONFIG>} to indicate the entry {@code ndk.ndk_path} from {@code .buckconfig}, or
   * the name of an environment variable that contains path to Android NDK ({@code ANDROID_NDK},
   * {@code NDK_HOME} or {@code ANDROID_NDK_REPOSITORY}).
   *
   * <p>If nothing is specified in {@code .buckconfig} the default order is: {@code
   * ANDROID_NDK_REPOSITORY}, {@code ANDROID_NDK}, {@code NDK_HOME}, {@code
   * <NDK_REPOSITORY_CONFIG>}, {@code <NDK_DIRECTORY_CONFIG>}
   */
  public ImmutableList<NdkSearchOrderEntry> getNdkSearchOrder() {
    return delegate
        .getOptionalListWithoutComments("ndk", "ndk_search_order")
        .map(
            configEntries ->
                ImmutableList.copyOf(
                    Iterables.transform(configEntries, this::convertNdkSearchEntry)))
        .orElse(DEFAULT_NDK_SEARCH_ORDER);
  }

  private NdkSearchOrderEntry convertNdkSearchEntry(String configEntry) {
    for (NdkSearchOrderEntry searchOrderEntry : NdkSearchOrderEntry.values()) {
      if (configEntry.equals(searchOrderEntry.entryValue)) {
        return searchOrderEntry;
      }
    }
    throw new HumanReadableException(
        "Unknown ndk.ndk_search_order entry: %s. Supported values: %s",
        configEntry, ImmutableList.of(NdkSearchOrderEntry.values()));
  }

  public Optional<String> getNdkCpuAbiFallbackAppPlatform() {
    return delegate.getValue("ndk", "app_platform");
  }

  public ImmutableMap<String, String> getNdkCpuAbiAppPlatformMap() {
    return delegate.getMap("ndk", "app_platform_per_cpu_abi");
  }

  public Optional<ImmutableSet<String>> getNdkCpuAbis() {
    return delegate.getOptionalListWithoutComments("ndk", "cpu_abis").map(ImmutableSet::copyOf);
  }

  public Optional<NdkCompilerType> getNdkCompiler() {
    return delegate.getEnum("ndk", "compiler", NdkCompilerType.class);
  }

  public Optional<String> getNdkGccVersion() {
    return delegate.getValue("ndk", "gcc_version");
  }

  public Optional<String> getNdkClangVersion() {
    return delegate.getValue("ndk", "clang_version");
  }

  public Optional<NdkCxxRuntime> getNdkCxxRuntime() {
    return delegate.getEnum("ndk", "cxx_runtime", NdkCxxRuntime.class);
  }

  public Optional<NdkCxxRuntimeType> getNdkCxxRuntimeType() {
    return delegate.getEnum("ndk", "cxx_runtime_type", NdkCxxRuntimeType.class);
  }

  public ImmutableList<String> getExtraNdkCFlags() {
    return delegate.getListWithoutComments("ndk", "extra_cflags", ' ');
  }

  public ImmutableList<String> getExtraNdkCxxFlags() {
    return delegate.getListWithoutComments("ndk", "extra_cxxflags", ' ');
  }

  public ImmutableList<String> getExtraNdkLdFlags() {
    return delegate.getListWithoutComments("ndk", "extra_ldflags", ' ');
  }

  public Optional<Boolean> getNdkUnifiedHeaders() {
    return delegate.getBoolean("ndk", "use_unified_headers");
  }

  public boolean isGrayscaleImageProcessingEnabled() {
    return delegate.getBooleanValue(
        "android",
        "resource_grayscale_enabled",
        // TODO: `android.resource_grayscale_enabled` is the canonical value. We used to use
        // `resources.resource_grayscale_enabled` though, so temporarily use that as a fallback to
        // allow users to migrate.
        delegate.getBooleanValue("resources", "resource_grayscale_enabled", false));
  }

  /**
   * Returns the CPU specific app platform, or the fallback one if set. If neither are set, returns
   * `Optional.empty` instead of a default value so callers can determine the difference between
   * user-set and buck defaults.
   */
  public Optional<String> getNdkAppPlatformForCpuAbi(String cpuAbi) {
    ImmutableMap<String, String> platformMap = getNdkCpuAbiAppPlatformMap();
    Optional<String> specificAppPlatform = Optional.ofNullable(platformMap.get(cpuAbi));
    return specificAppPlatform.isPresent()
        ? specificAppPlatform
        : getNdkCpuAbiFallbackAppPlatform();
  }

  /** Gets the ndk_toolchain target for the abi if it is specified in the config. */
  public Optional<BuildTarget> getNdkCxxToolchainTargetForAbi(
      String cpuAbi, TargetConfiguration targetConfiguration) {
    ImmutableMap<String, String> platformMap =
        delegate.getMap("ndk", "toolchain_target_per_cpu_abi");
    platformMap.keySet().forEach(key -> Verify.verify(VALID_ABI_KEYS.contains(key)));
    Optional<String> platformTarget = Optional.ofNullable(platformMap.get(cpuAbi));
    return platformTarget.map(
        target -> delegate.getBuildTargetForFullyQualifiedTarget(target, targetConfiguration));
  }

  /**
   * Returns the path to the platform specific aapt executable that is overridden by the current
   * project. If not specified, the Android platform aapt will be used.
   */
  public Optional<Supplier<Tool>> getAaptOverride() {
    return getToolOverride("aapt");
  }

  /**
   * Returns the path to the platform specific aapt2 executable that is overridden by the current
   * project. If not specified, the Android platform aapt will be used.
   */
  public Optional<Supplier<Tool>> getAapt2Override() {
    return getToolOverride("aapt2");
  }

  public Optional<BuildTarget> getRedexTarget(TargetConfiguration targetConfiguration) {
    return delegate.getMaybeBuildTarget(ANDROID_SECTION, REDEX, targetConfiguration);
  }

  public Tool getRedexTool(
      BuildRuleResolver buildRuleResolver, TargetConfiguration targetConfiguration) {
    Optional<Tool> redexBinary =
        delegate
            .getView(ToolConfig.class)
            .getTool(ANDROID_SECTION, REDEX, buildRuleResolver, targetConfiguration);
    if (!redexBinary.isPresent()) {
      throw new HumanReadableException(
          "Requested running ReDex but the path to the tool"
              + "has not been specified in the %s.%s .buckconfig section.",
          ANDROID_SECTION, REDEX);
    }
    return redexBinary.get();
  }

  private Optional<Supplier<Tool>> getToolOverride(String tool) {
    Optional<String> pathString = delegate.getValue("tools", tool);
    if (!pathString.isPresent()) {
      return Optional.empty();
    }

    String platformDir;
    if (platform == Platform.LINUX) {
      platformDir = "linux";
    } else if (platform == Platform.MACOS) {
      platformDir = "mac";
    } else if (platform == Platform.WINDOWS) {
      platformDir = "windows";
    } else {
      return Optional.empty();
    }

    return Optional.of(
        () -> {
          Optional<Tool> optionalTool =
              delegate
                  .getView(ToolConfig.class)
                  .getPrebuiltTool("tools", tool, value -> Paths.get(value, platformDir, tool));
          // Should be present because we verified that the value is present above.
          Preconditions.checkState(optionalTool.isPresent());
          return optionalTool.get();
        });
  }
}
