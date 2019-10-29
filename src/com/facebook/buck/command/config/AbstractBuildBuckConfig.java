/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.command.config;

import static java.lang.Integer.parseInt;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.google.common.annotations.VisibleForTesting;
import java.util.Optional;
import org.immutables.value.Value;

/** Some configuration options that may affect the build in some way. */
@BuckStyleTuple
@Value.Immutable(builder = false, copy = false)
public abstract class AbstractBuildBuckConfig implements ConfigView<BuckConfig> {

  private static final Float DEFAULT_THREAD_CORE_RATIO = 1.0F;

  private static final String BUILD_SECTION = "build";
  private static final String PROJECT_SECTION = "project";
  private static final String TARGETS_SECTION = "targets";
  private static final String CACHE_SECTION = "cache";

  @Override
  public abstract BuckConfig getDelegate();

  @Value.Lazy
  public int getMaxActionGraphCacheEntries() {
    return getDelegate().getInteger(CACHE_SECTION, "max_action_graph_cache_entries").orElse(1);
  }

  /**
   * Whether Buck should use Buck binary hash or git commit id as the core key in all rule keys.
   *
   * <p>The binary hash reflects the code that can affect the content of artifacts.
   *
   * <p>By default git commit id is used as the core key.
   *
   * @return <code>True</code> if binary hash should be used as the core key
   */
  @Value.Lazy
  public boolean useBuckBinaryHash() {
    return getDelegate().getBooleanValue(CACHE_SECTION, "use_buck_binary_hash", false);
  }

  @Value.Lazy
  public int getKeySeed() {
    return parseInt(getDelegate().getValue(CACHE_SECTION, "key_seed").orElse("0"));
  }

  /** @return the number of threads Buck should use. */
  @Value.Lazy
  public int getNumThreads() {
    return getNumThreads(getDefaultMaximumNumberOfThreads());
  }

  /** @return the number of threads to be used for the scheduled executor thread pool. */
  @Value.Lazy
  public int getNumThreadsForSchedulerPool() {
    return getDelegate().getLong(BUILD_SECTION, "scheduler_threads").orElse((long) 2).intValue();
  }

  /** @return the maximum size of files input based rule keys will be willing to hash. */
  @Value.Lazy
  public long getBuildInputRuleKeyFileSizeLimit() {
    return getDelegate()
        .getLong(BUILD_SECTION, "input_rule_key_file_size_limit")
        .orElse(Long.MAX_VALUE);
  }

  @Value.Lazy
  public int getDefaultMaximumNumberOfThreads() {
    return getDefaultMaximumNumberOfThreads(Runtime.getRuntime().availableProcessors());
  }

  @VisibleForTesting
  int getDefaultMaximumNumberOfThreads(int detectedProcessorCount) {
    double ratio =
        getDelegate()
            .getFloat(BUILD_SECTION, "thread_core_ratio")
            .orElse(DEFAULT_THREAD_CORE_RATIO);
    if (ratio <= 0.0F) {
      throw new HumanReadableException(
          "thread_core_ratio must be greater than zero (was " + ratio + ")");
    }

    int scaledValue = (int) Math.ceil(ratio * detectedProcessorCount);

    int threadLimit = detectedProcessorCount;

    Optional<Long> reservedCores = getNumberOfReservedCores();
    if (reservedCores.isPresent()) {
      threadLimit -= reservedCores.get();
    }

    if (scaledValue > threadLimit) {
      scaledValue = threadLimit;
    }

    Optional<Long> minThreads = getThreadCoreRatioMinThreads();
    if (minThreads.isPresent()) {
      scaledValue = Math.max(scaledValue, minThreads.get().intValue());
    }

    Optional<Long> maxThreads = getThreadCoreRatioMaxThreads();
    if (maxThreads.isPresent()) {
      long maxThreadsValue = maxThreads.get();

      if (minThreads.isPresent() && minThreads.get() > maxThreadsValue) {
        throw new HumanReadableException(
            "thread_core_ratio_max_cores must be larger than thread_core_ratio_min_cores");
      }

      if (maxThreadsValue > threadLimit) {
        throw new HumanReadableException(
            "thread_core_ratio_max_cores is larger than thread_core_ratio_reserved_cores allows");
      }

      scaledValue = Math.min(scaledValue, (int) maxThreadsValue);
    }

    if (scaledValue <= 0) {
      throw new HumanReadableException(
          "Configuration resulted in an invalid number of build threads (" + scaledValue + ").");
    }

    return scaledValue;
  }

  private Optional<Long> getNumberOfReservedCores() {
    Optional<Long> reservedCores =
        getDelegate().getLong(BUILD_SECTION, "thread_core_ratio_reserved_cores");
    if (reservedCores.isPresent() && reservedCores.get() < 0) {
      throw new HumanReadableException("thread_core_ratio_reserved_cores must be larger than zero");
    }
    return reservedCores;
  }

  private Optional<Long> getThreadCoreRatioMaxThreads() {
    Optional<Long> maxThreads =
        getDelegate().getLong(BUILD_SECTION, "thread_core_ratio_max_threads");
    if (maxThreads.isPresent() && maxThreads.get() < 0) {
      throw new HumanReadableException("thread_core_ratio_max_threads must be larger than zero");
    }
    return maxThreads;
  }

  private Optional<Long> getThreadCoreRatioMinThreads() {
    Optional<Long> minThreads =
        getDelegate().getLong(BUILD_SECTION, "thread_core_ratio_min_threads");
    if (minThreads.isPresent() && minThreads.get() <= 0) {
      throw new HumanReadableException("thread_core_ratio_min_threads must be larger than zero");
    }
    return minThreads;
  }

  /**
   * @return the number of threads Buck should use or the specified defaultValue if it is not set.
   */
  public int getNumThreads(int defaultValue) {
    return getDelegate().getLong(BUILD_SECTION, "threads").orElse((long) defaultValue).intValue();
  }

  /**
   * @return whether to symlink the default output location (`buck-out`) to the user-provided
   *     override for compatibility.
   */
  @Value.Lazy
  public boolean getBuckOutCompatLink() {
    return getDelegate().getBooleanValue(PROJECT_SECTION, "buck_out_compat_link", false);
  }

  /** @return whether to enabled versions on build/test command. */
  @Value.Lazy
  public boolean getBuildVersions() {
    return getDelegate().getBooleanValue(BUILD_SECTION, "versions", false);
  }

  /** @return whether to enabled versions on targets command. */
  @Value.Lazy
  public boolean getTargetsVersions() {
    return getDelegate().getBooleanValue(TARGETS_SECTION, "versions", false);
  }

  /** @return whether to enable caching of rule key calculations between builds. */
  @Value.Lazy
  public boolean getRuleKeyCaching() {
    return getDelegate().getBooleanValue(BUILD_SECTION, "rule_key_caching", false);
  }

  /** Whether to create symlinks of build output in buck-out/last. */
  @Value.Lazy
  public boolean createBuildOutputSymLinksEnabled() {
    return getDelegate()
        .getBooleanValue(BUILD_SECTION, "create_build_output_symlinks_enabled", false);
  }

  @Value.Lazy
  public boolean isEmbeddedCellBuckOutEnabled() {
    return getDelegate().getBooleanValue(PROJECT_SECTION, "embedded_cell_buck_out_enabled", false);
  }

  @Value.Lazy
  public Optional<String> getPathToBuildPrehookScript() {
    return getDelegate().getValue(BUILD_SECTION, "prehook_script");
  }

  /**
   * Whether to delete temporary files generated to run a build rule immediately after the rule is
   * run.
   */
  @Value.Lazy
  public boolean getShouldDeleteTemporaries() {
    return getDelegate().getBooleanValue(BUILD_SECTION, "delete_temporaries", false);
  }

  /** @return whether to enable new file hash cache engine. */
  @Value.Lazy
  public FileHashCacheMode getFileHashCacheMode() {
    return getDelegate()
        .getEnum("build", "file_hash_cache_mode", FileHashCacheMode.class)
        .orElse(FileHashCacheMode.DEFAULT);
  }
}
