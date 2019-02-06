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

package com.facebook.buck.core.config;

import static java.lang.Integer.parseInt;

import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.facebook.infer.annotation.PropagatesNullable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Structured representation of data read from a {@code .buckconfig} file. */
public class BuckConfig {

  private static final Float DEFAULT_THREAD_CORE_RATIO = Float.valueOf(1.0F);

  private static final ImmutableMap<String, ImmutableSet<String>> IGNORE_FIELDS_FOR_DAEMON_RESTART;

  private final Architecture architecture;

  private final Config config;

  private final ProjectFilesystem projectFilesystem;

  private final Platform platform;

  private final ImmutableMap<String, String> environment;

  private final ConfigViewCache<BuckConfig> viewCache =
      new ConfigViewCache<>(this, BuckConfig.class);

  private final Function<String, UnconfiguredBuildTarget> buildTargetParser;

  private final int hashCode;

  static {
    ImmutableMap.Builder<String, ImmutableSet<String>> ignoreFieldsForDaemonRestartBuilder =
        ImmutableMap.builder();
    ignoreFieldsForDaemonRestartBuilder.put(
        "apple", ImmutableSet.of("generate_header_symlink_tree_only"));
    ignoreFieldsForDaemonRestartBuilder.put("build", ImmutableSet.of("threads"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "cache",
        ImmutableSet.of("dir", "dir_mode", "http_mode", "http_url", "mode", "slb_server_pool"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "client", ImmutableSet.of("id", "skip-action-graph-cache"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "log",
        ImmutableSet.of(
            "chrome_trace_generation",
            "compress_traces",
            "max_traces",
            "public_announcements",
            "log_build_id_to_console_enabled",
            "build_details_template"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "project", ImmutableSet.of("ide_prompt", "ide_force_kill"));
    ignoreFieldsForDaemonRestartBuilder.put(
        "ui",
        ImmutableSet.of(
            "superconsole",
            "thread_line_limit",
            "thread_line_output_max_columns",
            "warn_on_config_file_overrides",
            "warn_on_config_file_overrides_ignored_files"));
    ignoreFieldsForDaemonRestartBuilder.put("color", ImmutableSet.of("ui"));
    IGNORE_FIELDS_FOR_DAEMON_RESTART = ignoreFieldsForDaemonRestartBuilder.build();
  }

  public BuckConfig(
      Config config,
      ProjectFilesystem projectFilesystem,
      Architecture architecture,
      Platform platform,
      ImmutableMap<String, String> environment,
      Function<String, UnconfiguredBuildTarget> buildTargetParser) {
    this.config = config;
    this.projectFilesystem = projectFilesystem;
    this.architecture = architecture;

    this.platform = platform;
    this.environment = environment;
    this.buildTargetParser = buildTargetParser;

    this.hashCode = Objects.hashCode(config);
  }

  /** Returns a clone of the current config with a the argument CellPathResolver. */
  public BuckConfig withBuildTargetParser(
      Function<String, UnconfiguredBuildTarget> buildTargetParser) {
    return new BuckConfig(
        config, projectFilesystem, architecture, platform, environment, buildTargetParser);
  }

  /**
   * Get a {@link ConfigView} of this config.
   *
   * @param cls Class of the config view.
   * @param <T> Type of the config view.
   */
  public <T extends ConfigView<BuckConfig>> T getView(Class<T> cls) {
    return viewCache.getView(cls);
  }

  public Architecture getArchitecture() {
    return architecture;
  }

  public ImmutableMap<String, String> getEntriesForSection(String section) {
    ImmutableMap<String, String> entries = config.get(section);
    if (entries != null) {
      return entries;
    } else {
      return ImmutableMap.of();
    }
  }

  public ImmutableList<String> getMessageOfTheDay() {
    return getListWithoutComments("project", "motd");
  }

  public ImmutableList<String> getListWithoutComments(String section, String field) {
    return config.getListWithoutComments(section, field);
  }

  public ImmutableList<String> getListWithoutComments(
      String section, String field, char splitChar) {
    return config.getListWithoutComments(section, field, splitChar);
  }

  public Optional<ImmutableList<String>> getOptionalListWithoutComments(
      String section, String field) {
    return config.getOptionalListWithoutComments(section, field);
  }

  public Optional<ImmutableList<String>> getOptionalListWithoutComments(
      String section, String field, char splitChar) {
    return config.getOptionalListWithoutComments(section, field, splitChar);
  }

  public Optional<ImmutableList<Path>> getOptionalPathList(
      String section, String field, boolean resolve) {
    Optional<ImmutableList<String>> rawPaths =
        config.getOptionalListWithoutComments(section, field);

    if (!rawPaths.isPresent()) {
      return Optional.empty();
    }

    Stream<Path> paths = rawPaths.get().stream().map(this::getPathFromVfs);
    if (resolve) {
      paths = paths.map(projectFilesystem::getPathForRelativePath);
    }
    paths = paths.filter(projectFilesystem::exists);

    return Optional.of(paths.collect(ImmutableList.toImmutableList()));
  }

  public BuildTarget getBuildTargetForFullyQualifiedTarget(
      String target, TargetConfiguration targetConfiguration) {
    return buildTargetParser.apply(target).configure(targetConfiguration);
  }

  public ImmutableList<BuildTarget> getBuildTargetList(
      String section, String key, TargetConfiguration targetConfiguration) {
    ImmutableList<String> targetsToForce = getListWithoutComments(section, key);
    if (targetsToForce.isEmpty()) {
      return ImmutableList.of();
    }
    // TODO(cjhopman): Should this be moved to AliasConfig? It depends on that. Should AliasConfig
    // expose this logic here as a separate function?
    ImmutableList.Builder<BuildTarget> targets = new ImmutableList.Builder<>();
    for (String targetOrAlias : targetsToForce) {
      Set<String> expandedAlias =
          getView(AliasConfig.class).getBuildTargetForAliasAsString(targetOrAlias);
      if (expandedAlias.isEmpty()) {
        targets.add(getBuildTargetForFullyQualifiedTarget(targetOrAlias, targetConfiguration));
      } else {
        for (String target : expandedAlias) {
          targets.add(getBuildTargetForFullyQualifiedTarget(target, targetConfiguration));
        }
      }
    }
    return targets.build();
  }

  /** @return the parsed BuildTarget in the given section and field, if set. */
  public Optional<BuildTarget> getBuildTarget(
      String section, String field, TargetConfiguration targetConfiguration) {
    try {
      Optional<String> target = getValue(section, field);
      return target.map(
          targetName -> getBuildTargetForFullyQualifiedTarget(targetName, targetConfiguration));
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(
          e, "When trying to parse configuration %s.%s as a build target.", section, field);
    }
  }

  /**
   * @return the parsed BuildTarget in the given section and field, if set and a valid build target.
   *     <p>This is useful if you use getTool to get the target, if any, but allow filesystem
   *     references.
   */
  public Optional<BuildTarget> getMaybeBuildTarget(
      String section, String field, TargetConfiguration targetConfiguration) {
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    try {
      return Optional.of(getBuildTargetForFullyQualifiedTarget(value.get(), targetConfiguration));
    } catch (BuildTargetParseException e) {
      return Optional.empty();
    }
  }

  /** @return the parsed BuildTarget in the given section and field. */
  public BuildTarget getRequiredBuildTarget(
      String section, String field, TargetConfiguration targetConfiguration) {
    Optional<BuildTarget> target = getBuildTarget(section, field, targetConfiguration);
    return getOrThrow(section, field, target);
  }

  public <T extends Enum<T>> Optional<T> getEnum(String section, String field, Class<T> clazz) {
    return config.getEnum(section, field, clazz);
  }

  /**
   * @return a {@link SourcePath} identified by a @{link BuildTarget} or {@link Path} reference by
   *     the given section:field, if set.
   */
  public Optional<SourcePath> getSourcePath(
      String section, String field, TargetConfiguration targetConfiguration) {
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    try {
      BuildTarget target = getBuildTargetForFullyQualifiedTarget(value.get(), targetConfiguration);
      return Optional.of(DefaultBuildTargetSourcePath.of(target));
    } catch (BuildTargetParseException e) {
      return Optional.of(
          PathSourcePath.of(
              projectFilesystem,
              checkPathExists(
                  value.get(), String.format("Overridden %s:%s path not found", section, field))));
    }
  }

  /** @return a {@link SourcePath} identified by a {@link Path}. */
  public PathSourcePath getPathSourcePath(@PropagatesNullable Path path) {
    return getPathSourcePath(path, "File not found");
  }

  /**
   * @return a {@link SourcePath} identified by a {@link Path}.
   * @param errorMessage the error message to throw if path is not found
   */
  public PathSourcePath getPathSourcePath(@PropagatesNullable Path path, String errorMessage) {
    if (path == null) {
      return null;
    }
    if (path.isAbsolute()) {
      return PathSourcePath.of(projectFilesystem, path);
    }
    return PathSourcePath.of(projectFilesystem, checkPathExists(path.toString(), errorMessage));
  }

  public boolean getFlushEventsBeforeExit() {
    return getBooleanValue("daemon", "flush_events_before_exit", false);
  }

  public Path resolvePathThatMayBeOutsideTheProjectFilesystem(@PropagatesNullable Path path) {
    if (path == null) {
      return path;
    }
    return resolveNonNullPathOutsideTheProjectFilesystem(path);
  }

  public Path resolveNonNullPathOutsideTheProjectFilesystem(Path path) {
    if (path.isAbsolute()) {
      return getPathFromVfs(path);
    }

    Path expandedPath = MorePaths.expandHomeDir(path);
    return projectFilesystem.resolve(expandedPath);
  }

  public String getLocalhost() {
    try {
      return HostnameFetching.getHostname();
    } catch (IOException e) {
      return "<unknown>";
    }
  }

  public Platform getPlatform() {
    return platform;
  }

  public int getMaxActionGraphCacheEntries() {
    return getInteger("cache", "max_action_graph_cache_entries").orElse(1);
  }

  public Optional<String> getRepository() {
    return config.get("cache", "repository");
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
  public boolean useBuckBinaryHash() {
    return getBooleanValue("cache", "use_buck_binary_hash", false);
  }

  public boolean hasUserDefinedValue(String sectionName, String propertyName) {
    return config.get(sectionName).containsKey(propertyName);
  }

  public Optional<ImmutableMap<String, String>> getSection(String sectionName) {
    ImmutableMap<String, String> values = config.get(sectionName);
    return values.isEmpty() ? Optional.empty() : Optional.of(values);
  }

  /**
   * @return the string value for the config settings, where present empty values are {@code
   *     Optional.empty()}.
   */
  public Optional<String> getValue(String sectionName, String propertyName) {
    return config.getValue(sectionName, propertyName);
  }

  /**
   * @return the string value for the config settings, where present empty values are {@code
   *     Optional[]}.
   */
  public Optional<String> getRawValue(String sectionName, String propertyName) {
    return config.get(sectionName, propertyName);
  }

  public OptionalInt getInteger(String sectionName, String propertyName) {
    return config.getInteger(sectionName, propertyName);
  }

  public Optional<Long> getLong(String sectionName, String propertyName) {
    return config.getLong(sectionName, propertyName);
  }

  public Optional<Float> getFloat(String sectionName, String propertyName) {
    return config.getFloat(sectionName, propertyName);
  }

  public Optional<Boolean> getBoolean(String sectionName, String propertyName) {
    return config.getBoolean(sectionName, propertyName);
  }

  public boolean getBooleanValue(String sectionName, String propertyName, boolean defaultValue) {
    return config.getBooleanValue(sectionName, propertyName, defaultValue);
  }

  public Optional<URI> getUrl(String section, String field) {
    return config.getUrl(section, field);
  }

  public ImmutableMap<String, String> getMap(String section, String field) {
    return config.getMap(section, field);
  }

  /** Returns the probabilities for each group in an experiment. */
  public <T extends Enum<T>> Map<T, Double> getExperimentGroups(
      String section, String field, Class<T> enumClass) {
    return getMap(section, field)
        .entrySet()
        .stream()
        .collect(
            ImmutableMap.toImmutableMap(
                x -> Enum.valueOf(enumClass, x.getKey().toUpperCase(Locale.ROOT)),
                x -> Double.parseDouble(x.getValue())));
  }

  public <T> T getOrThrow(String section, String field, Optional<T> value) {
    if (!value.isPresent()) {
      throw new HumanReadableException(
          String.format(".buckconfig: %s:%s must be set", section, field));
    }
    return value.get();
  }

  // This is a hack. A cleaner approach would be to expose a narrow view of the config to any code
  // that affects the state cached by the Daemon.
  public boolean equalsForDaemonRestart(BuckConfig other) {
    return this.config.equalsIgnoring(other.config, IGNORE_FIELDS_FOR_DAEMON_RESTART);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (!(obj instanceof BuckConfig)) {
      return false;
    }
    BuckConfig that = (BuckConfig) obj;
    return this.hashCode == that.hashCode;
  }

  @Override
  public String toString() {
    return String.format("%s (config=%s)", super.toString(), config);
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  public ImmutableMap<String, String> getEnvironment() {
    return environment;
  }

  public String[] getEnv(String propertyName, String separator) {
    String value = getEnvironment().get(propertyName);
    if (value == null) {
      value = "";
    }
    return value.split(separator);
  }
  /** @return the local cache directory */
  public String getLocalCacheDirectory(String dirCacheName) {
    return getValue(dirCacheName, "dir")
        .orElse(projectFilesystem.getBuckPaths().getCacheDir().toString());
  }

  public int getKeySeed() {
    return parseInt(getValue("cache", "key_seed").orElse("0"));
  }

  /** @return the path for the given section and property. */
  public Optional<Path> getPath(String sectionName, String name) {
    return getPath(sectionName, name, true);
  }

  public Path getRequiredPath(String section, String field) {
    Optional<Path> path = getPath(section, field);
    return getOrThrow(section, field, path);
  }

  /** @return the number of threads Buck should use. */
  public int getNumThreads() {
    return getNumThreads(getDefaultMaximumNumberOfThreads());
  }

  /** @return the number of threads to be used for the scheduled executor thread pool. */
  public int getNumThreadsForSchedulerPool() {
    return config.getLong("build", "scheduler_threads").orElse((long) 2).intValue();
  }

  /** @return the maximum size of files input based rule keys will be willing to hash. */
  public long getBuildInputRuleKeyFileSizeLimit() {
    return config.getLong("build", "input_rule_key_file_size_limit").orElse(Long.MAX_VALUE);
  }

  public int getDefaultMaximumNumberOfThreads() {
    return getDefaultMaximumNumberOfThreads(Runtime.getRuntime().availableProcessors());
  }

  @VisibleForTesting
  int getDefaultMaximumNumberOfThreads(int detectedProcessorCount) {
    double ratio = config.getFloat("build", "thread_core_ratio").orElse(DEFAULT_THREAD_CORE_RATIO);
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
    Optional<Long> reservedCores = config.getLong("build", "thread_core_ratio_reserved_cores");
    if (reservedCores.isPresent() && reservedCores.get() < 0) {
      throw new HumanReadableException("thread_core_ratio_reserved_cores must be larger than zero");
    }
    return reservedCores;
  }

  private Optional<Long> getThreadCoreRatioMaxThreads() {
    Optional<Long> maxThreads = config.getLong("build", "thread_core_ratio_max_threads");
    if (maxThreads.isPresent() && maxThreads.get() < 0) {
      throw new HumanReadableException("thread_core_ratio_max_threads must be larger than zero");
    }
    return maxThreads;
  }

  private Optional<Long> getThreadCoreRatioMinThreads() {
    Optional<Long> minThreads = config.getLong("build", "thread_core_ratio_min_threads");
    if (minThreads.isPresent() && minThreads.get() <= 0) {
      throw new HumanReadableException("thread_core_ratio_min_threads must be larger than zero");
    }
    return minThreads;
  }

  /**
   * @return the number of threads Buck should use or the specified defaultValue if it is not set.
   */
  public int getNumThreads(int defaultValue) {
    return config.getLong("build", "threads").orElse((long) defaultValue).intValue();
  }

  public Optional<ImmutableList<String>> getAllowedJavaSpecificationVersions() {
    return getOptionalListWithoutComments("project", "allowed_java_specification_versions");
  }

  public long getCountersFirstFlushIntervalMillis() {
    return config.getLong("counters", "first_flush_interval_millis").orElse(5000L);
  }

  public long getCountersFlushIntervalMillis() {
    return config.getLong("counters", "flush_interval_millis").orElse(30000L);
  }

  public Optional<Path> getPath(String sectionName, String name, boolean isCellRootRelative) {
    Optional<String> pathString = getValue(sectionName, name);
    return pathString.map(
        path ->
            convertPathWithError(
                path,
                isCellRootRelative,
                String.format("Overridden %s:%s path not found", sectionName, name)));
  }

  /**
   * Return a {@link Path} from the underlying {@link java.nio.file.FileSystem} implementation. This
   * allows to safely call {@link Path#resolve(Path)} and similar calls without exceptions caused by
   * mis-matched underlying filesystem implementations causing grief. This is particularly useful
   * for those times where we're using (eg) JimFs for our testing.
   */
  private Path getPathFromVfs(String path) {
    return projectFilesystem.getPath(path);
  }

  private Path getPathFromVfs(Path path) {
    return projectFilesystem.getPath(path.toString());
  }

  private Path convertPathWithError(String pathString, boolean isCellRootRelative, String error) {
    return isCellRootRelative
        ? checkPathExistsAndResolve(pathString, error)
        : getPathFromVfs(pathString);
  }

  public Path checkPathExistsAndResolve(String pathString, String errorMsg) {
    return projectFilesystem.getPathForRelativePath(checkPathExists(pathString, errorMsg));
  }

  private Path checkPathExists(String pathString, String errorMsg) {
    Path path = getPathFromVfs(pathString);
    if (projectFilesystem.exists(path)) {
      return path;
    }
    throw new HumanReadableException(String.format("%s: %s", errorMsg, path));
  }

  public ImmutableSet<String> getSections() {
    return config.getSectionToEntries().keySet();
  }

  public ImmutableMap<String, ImmutableMap<String, String>> getRawConfigForParser() {
    ImmutableMap<String, ImmutableMap<String, String>> rawSections = config.getSectionToEntries();

    // If the raw config doesn't have sections which have ignored fields, then just return it as-is.
    ImmutableSet<String> sectionsWithIgnoredFields = IGNORE_FIELDS_FOR_DAEMON_RESTART.keySet();
    if (Sets.intersection(rawSections.keySet(), sectionsWithIgnoredFields).isEmpty()) {
      return rawSections;
    }

    // Otherwise, iterate through the config to do finer-grain filtering.
    ImmutableMap.Builder<String, ImmutableMap<String, String>> filtered = ImmutableMap.builder();
    for (Map.Entry<String, ImmutableMap<String, String>> sectionEnt : rawSections.entrySet()) {
      String sectionName = sectionEnt.getKey();

      // If this section doesn't have a corresponding ignored section, then just add it as-is.
      if (!sectionsWithIgnoredFields.contains(sectionName)) {
        filtered.put(sectionEnt);
        continue;
      }

      // If none of this section's entries are ignored, then add it as-is.
      ImmutableMap<String, String> fields = sectionEnt.getValue();
      ImmutableSet<String> ignoredFieldNames =
          IGNORE_FIELDS_FOR_DAEMON_RESTART.getOrDefault(sectionName, ImmutableSet.of());
      if (Sets.intersection(fields.keySet(), ignoredFieldNames).isEmpty()) {
        filtered.put(sectionEnt);
        continue;
      }

      // Otherwise, filter out the ignored fields.
      ImmutableMap<String, String> remainingKeys =
          ImmutableMap.copyOf(Maps.filterKeys(fields, Predicates.not(ignoredFieldNames::contains)));

      if (!remainingKeys.isEmpty()) {
        filtered.put(sectionName, remainingKeys);
      }
    }

    return filtered.build();
  }

  /**
   * @return whether to symlink the default output location (`buck-out`) to the user-provided
   *     override for compatibility.
   */
  public boolean getBuckOutCompatLink() {
    return getBooleanValue("project", "buck_out_compat_link", false);
  }

  /** @return whether to enabled versions on build/test command. */
  public boolean getBuildVersions() {
    return getBooleanValue("build", "versions", false);
  }

  /** @return whether to enabled versions on targets command. */
  public boolean getTargetsVersions() {
    return getBooleanValue("targets", "versions", false);
  }

  /** @return whether to enable caching of rule key calculations between builds. */
  public boolean getRuleKeyCaching() {
    return getBooleanValue("build", "rule_key_caching", false);
  }

  public ImmutableList<String> getCleanAdditionalPaths() {
    return getListWithoutComments("clean", "additional_paths");
  }

  public ImmutableList<String> getCleanExcludedCaches() {
    return getListWithoutComments("clean", "excluded_dir_caches");
  }

  /** @return whether to enable new file hash cache engine. */
  public FileHashCacheMode getFileHashCacheMode() {
    return getEnum("build", "file_hash_cache_mode", FileHashCacheMode.class)
        .orElse(FileHashCacheMode.DEFAULT);
  }

  public Config getConfig() {
    return config;
  }

  public boolean isLogBuildIdToConsoleEnabled() {
    return getBooleanValue("log", "log_build_id_to_console_enabled", false);
  }

  /** Whether to create symlinks of build output in buck-out/last. */
  public boolean createBuildOutputSymLinksEnabled() {
    return getBooleanValue("build", "create_build_output_symlinks_enabled", false);
  }

  public boolean isEmbeddedCellBuckOutEnabled() {
    return getBooleanValue("project", "embedded_cell_buck_out_enabled", false);
  }

  public Optional<String> getPathToBuildPrehookScript() {
    return getValue("build", "prehook_script");
  }

  /** List of error message replacements to make things more friendly for humans */
  public Map<Pattern, String> getErrorMessageAugmentations() throws HumanReadableException {
    return config
        .getMap("ui", "error_message_augmentations")
        .entrySet()
        .stream()
        .collect(
            ImmutableMap.toImmutableMap(
                e -> {
                  try {
                    return Pattern.compile(e.getKey(), Pattern.MULTILINE | Pattern.DOTALL);
                  } catch (Exception ex) {
                    throw new HumanReadableException(
                        "Could not parse regular expression %s from buckconfig: %s",
                        e.getKey(), ex.getMessage());
                  }
                },
                Entry::getValue));
  }

  /**
   * Whether to delete temporary files generated to run a build rule immediately after the rule is
   * run.
   */
  public boolean getShouldDeleteTemporaries() {
    return config.getBooleanValue("build", "delete_temporaries", false);
  }

  public Optional<String> getBuildDetailsTemplate() {
    return config.get("log", "build_details_template");
  }

  public ProjectFilesystem getFilesystem() {
    return projectFilesystem;
  }

  public boolean getWarnOnConfigFileOverrides() {
    return config.getBooleanValue("ui", "warn_on_config_file_overrides", true);
  }

  public ImmutableSet<Path> getWarnOnConfigFileOverridesIgnoredFiles() {
    return config
        .getListWithoutComments("ui", "warn_on_config_file_overrides_ignored_files", ',')
        .stream()
        .map(Paths::get)
        .collect(ImmutableSet.toImmutableSet());
  }
}
