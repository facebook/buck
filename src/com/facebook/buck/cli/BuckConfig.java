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

package com.facebook.buck.cli;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.network.HostnameFetching;
import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Structured representation of data read from a {@code .buckconfig} file.
 */
@Beta
@Immutable
public class BuckConfig {

  private static final Logger LOG = Logger.get(BuckConfig.class);

  public static final String DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME = ".buckconfig.local";

  private static final String ALIAS_SECTION_HEADER = "alias";

  /**
   * This pattern is designed so that a fully-qualified build target cannot be a valid alias name
   * and vice-versa.
   */
  private static final Pattern ALIAS_PATTERN = Pattern.compile("[a-zA-Z_-][a-zA-Z0-9_-]*");

  @VisibleForTesting
  static final String BUCK_BUCKD_DIR_KEY = "buck.buckd_dir";

  private static final String DEFAULT_MAX_TRACES = "25";

  private static final Function<String, URI> TO_URI = new Function<String, URI>() {
    @Override
    public URI apply(String input) {
      return URI.create(input);
    }
  };

  private final Config config;

  private final ImmutableMap<String, BuildTarget> aliasToBuildTargetMap;

  private final ProjectFilesystem projectFilesystem;

  private final Platform platform;

  private final ImmutableMap<String, String> environment;

  public BuckConfig(
      Config config,
      ProjectFilesystem projectFilesystem,
      Platform platform,
      ImmutableMap<String, String> environment) {
    this.config = config;
    this.projectFilesystem = projectFilesystem;

    // We could create this Map on demand; however, in practice, it is almost always needed when
    // BuckConfig is needed because CommandLineBuildTargetNormalizer needs it.
    this.aliasToBuildTargetMap = createAliasToBuildTargetMap(
        this.getEntriesForSection(ALIAS_SECTION_HEADER));

    this.platform = platform;
    this.environment = environment;
  }

  /**
   * @return whether {@code aliasName} conforms to the pattern for a valid alias name. This does not
   *     indicate whether it is an alias that maps to a build target in a BuckConfig.
   */
  private static boolean isValidAliasName(String aliasName) {
    return ALIAS_PATTERN.matcher(aliasName).matches();
  }

  public static void validateAliasName(String aliasName) throws HumanReadableException {
    validateAgainstAlias(aliasName, "Alias");
  }

  public static void validateLabelName(String aliasName) throws HumanReadableException {
    validateAgainstAlias(aliasName, "Label");
  }

  private static void validateAgainstAlias(String aliasName, String fieldName) {
    if (isValidAliasName(aliasName)) {
      return;
    }

    if (aliasName.isEmpty()) {
      throw new HumanReadableException("%s cannot be the empty string.", fieldName);
    }

    throw new HumanReadableException("Not a valid %s: %s.", fieldName.toLowerCase(), aliasName);
  }

  @SafeVarargs
  @VisibleForTesting
  static BuckConfig createFromReaders(
      Map<Path, Reader> readers,
      ProjectFilesystem projectFilesystem,
      Platform platform,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, ImmutableMap<String, String>>... configOverrides)
  throws IOException {
    ImmutableList.Builder<ImmutableMap<String, ImmutableMap<String, String>>> builder =
        ImmutableList.builder();
    for (Map.Entry<Path, Reader> entry : readers.entrySet()) {
      Path filePath = entry.getKey();
      Reader reader = entry.getValue();
      ImmutableMap<String, ImmutableMap<String, String>> parsedConfiguration = Inis.read(reader);
      LOG.debug("Loaded a configuration file %s: %s", filePath, parsedConfiguration);
      builder.add(parsedConfiguration);
    }
    for (ImmutableMap<String, ImmutableMap<String, String>> configOverride : configOverrides) {
      LOG.debug("Adding configuration overrides: %s", configOverride);
      builder.add(configOverride);
    }
    Config config = new Config(builder.build());
    return new BuckConfig(
        config,
        projectFilesystem,
        platform,
        environment);
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

  @Nullable
  public String getBuildTargetForAliasAsString(String possiblyFlavoredAlias) {
    Pair<BuildTarget, Integer> buildTargetPoundIdx = getBuildTargetForAlias(possiblyFlavoredAlias);
    BuildTarget buildTarget = buildTargetPoundIdx.getFirst();
    int poundIdx = buildTargetPoundIdx.getSecond();
    if (buildTarget != null) {
      return buildTarget.getFullyQualifiedName() +
          (poundIdx == -1 ? "" : possiblyFlavoredAlias.substring(poundIdx));
    } else {
      return null;
    }
  }

  public Pair<BuildTarget, Integer> getBuildTargetForAlias(String possiblyFlavoredAlias) {
    String alias = possiblyFlavoredAlias;
    int poundIdx = possiblyFlavoredAlias.indexOf('#');
    if (poundIdx != -1) {
      alias = possiblyFlavoredAlias.substring(0, poundIdx);
    }
    BuildTarget buildTarget = aliasToBuildTargetMap.get(alias);
    return new Pair<>(buildTarget, poundIdx);
  }

  public BuildTarget getBuildTargetForFullyQualifiedTarget(String target) {
    return BuildTargetParser.INSTANCE.parse(
        target,
        BuildTargetPatternParser.fullyQualified());
  }

  /**
   * @return the parsed BuildTarget in the given section and field, if set.
   */
  public Optional<BuildTarget> getBuildTarget(String section, String field) {
    Optional<String> target = getValue(section, field);
    return target.isPresent() ?
        Optional.of(getBuildTargetForFullyQualifiedTarget(target.get())) :
        Optional.<BuildTarget>absent();
  }

  /**
   * @return the parsed BuildTarget in the given section and field.
   */
  public BuildTarget getRequiredBuildTarget(String section, String field) {
    Optional<BuildTarget> target = getBuildTarget(section, field);
    return required(section, field, target);
  }

  public <T extends Enum<T>> Optional<T> getEnum(String section, String field, Class<T> clazz) {
    return config.getEnum(section, field, clazz);
  }

  public <T extends Enum<T>> T getRequiredEnum(String section, String field, Class<T> clazz) {
    Optional<T> value = getEnum(section, field, clazz);
    return required(section, field, value);
  }

  /**
   * @return a {@link SourcePath} identified by a @{link BuildTarget} or {@link Path} reference
   *     by the given section:field, if set.
   */
  public Optional<SourcePath> getSourcePath(String section, String field) {
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.absent();
    }
    try {
      BuildTarget target = getBuildTargetForFullyQualifiedTarget(value.get());
      return Optional.<SourcePath>of(new BuildTargetSourcePath(target));
    } catch (BuildTargetParseException e) {
      checkPathExists(
          value.get(),
          String.format("Overridden %s:%s path not found: ", section, field));
      return Optional.<SourcePath>of(
          new PathSourcePath(projectFilesystem, getPathFromVfs(value.get())));
    }
  }

  /**
   * @return a {@link SourcePath} identified by a @{link BuildTarget} or {@link Path} reference
   *     by the given section:field.
   */
  public SourcePath getRequiredSourcePath(String section, String field) {
    Optional<SourcePath> path = getSourcePath(section, field);
    return required(section, field, path);
  }

  /**
   * @return a {@link Tool} identified by a @{link BuildTarget} or {@link Path} reference
   *     by the given section:field, if set.
   */
  public Optional<Tool> getTool(String section, String field, BuildRuleResolver resolver) {
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.absent();
    }
    try {
      BuildTarget target = getBuildTargetForFullyQualifiedTarget(value.get());
      Optional<BuildRule> rule = resolver.getRuleOptional(target);
      if (!rule.isPresent()) {
        throw new HumanReadableException("[%s] %s: no rule found for %s", section, field, target);
      }
      if (!(rule.get() instanceof BinaryBuildRule)) {
        throw new HumanReadableException(
            "[%s] %s: %s must be an executable rule",
            section,
            field,
            target);
      }
      return Optional.of(((BinaryBuildRule) rule.get()).getExecutableCommand());
    } catch (BuildTargetParseException e) {
      checkPathExists(
          value.get(),
          String.format("Overridden %s:%s path not found: ", section, field));
      return Optional.<Tool>of(new HashedFileTool(getPathFromVfs(value.get())));
    }
  }

  public Tool getRequiredTool(String section, String field, BuildRuleResolver resolver) {
    Optional<Tool> path = getTool(section, field, resolver);
    return required(section, field, path);
  }

  /**
   * In a {@link BuckConfig}, an alias can either refer to a fully-qualified build target, or an
   * alias defined earlier in the {@code alias} section. The mapping produced by this method
   * reflects the result of resolving all aliases as values in the {@code alias} section.
   */
  private static ImmutableMap<String, BuildTarget> createAliasToBuildTargetMap(
      ImmutableMap<String, String> rawAliasMap) {
    // We use a LinkedHashMap rather than an ImmutableMap.Builder because we want both (1) order to
    // be preserved, and (2) the ability to inspect the Map while building it up.
    LinkedHashMap<String, BuildTarget> aliasToBuildTarget = Maps.newLinkedHashMap();
    for (Map.Entry<String, String> aliasEntry : rawAliasMap.entrySet()) {
      String alias = aliasEntry.getKey();
      validateAliasName(alias);

      // Determine whether the mapping is to a build target or to an alias.
      String value = aliasEntry.getValue();
      BuildTarget buildTarget;
      if (isValidAliasName(value)) {
        buildTarget = aliasToBuildTarget.get(value);
        if (buildTarget == null) {
          throw new HumanReadableException("No alias for: %s.", value);
        }
      } else {
        // Here we parse the alias values with a BuildTargetParser to be strict. We could be looser
        // and just grab everything between "//" and ":" and assume it's a valid base path.
        buildTarget = BuildTargetParser.INSTANCE.parse(
            value,
            BuildTargetPatternParser.fullyQualified());
      }
      aliasToBuildTarget.put(alias, buildTarget);
    }
    return ImmutableMap.copyOf(aliasToBuildTarget);
  }

  /**
   * Create a map of {@link BuildTarget} base paths to aliases. Note that there may be more than
   * one alias to a base path, so the first one listed in the .buckconfig will be chosen.
   */
  public ImmutableMap<Path, String> getBasePathToAliasMap() {
    ImmutableMap<String, String> aliases = config.get(ALIAS_SECTION_HEADER);
    if (aliases == null) {
      return ImmutableMap.of();
    }

    // Build up the Map with an ordinary HashMap because we need to be able to check whether the Map
    // already contains the key before inserting.
    Map<Path, String> basePathToAlias = Maps.newHashMap();
    for (Map.Entry<String, BuildTarget> entry : aliasToBuildTargetMap.entrySet()) {
      String alias = entry.getKey();
      BuildTarget buildTarget = entry.getValue();

      Path basePath = buildTarget.getBasePath();
      if (!basePathToAlias.containsKey(basePath)) {
        basePathToAlias.put(basePath, alias);
      }
    }
    return ImmutableMap.copyOf(basePathToAlias);
  }

  public ImmutableSet<String> getAliases() {
    return this.aliasToBuildTargetMap.keySet();
  }

  public long getDefaultTestTimeoutMillis() {
    return Long.parseLong(getValue("test", "timeout").or("0"));
  }

  public int getMaxTraces() {
    return Integer.parseInt(getValue("log", "max_traces").or(DEFAULT_MAX_TRACES));
  }

  public boolean getCompressTraces() {
    return getBooleanValue("log", "compress_traces", false);
  }

  public boolean getRestartAdbOnFailure() {
    return Boolean.parseBoolean(getValue("adb", "adb_restart_on_failure").or("true"));
  }

  public boolean getMultiInstallMode() {
    return getBooleanValue("adb", "multi_install_mode", false);
  }

  public boolean getFlushEventsBeforeExit() {
    return getBooleanValue("daemon", "flush_events_before_exit", false);
  }

  public ImmutableSet<String> getListenerJars() {
    return ImmutableSet.copyOf(getListWithoutComments("extensions", "listeners"));
  }

  public ImmutableSet<String> getSrcRoots() {
    return ImmutableSet.copyOf(getListWithoutComments("java", "src_roots"));
  }

  @VisibleForTesting
  DefaultJavaPackageFinder createDefaultJavaPackageFinder() {
    Set<String> srcRoots = getSrcRoots();
    return DefaultJavaPackageFinder.createDefaultJavaPackageFinder(srcRoots);
  }

  /**
   * Return Strings so as to avoid a dependency on {@link LabelSelector}!
   */
  ImmutableList<String> getDefaultRawExcludedLabelSelectors() {
    return getListWithoutComments("test", "excluded_labels");
  }

  /**
   * By default, running tests use a temporary directory under
   * buck-out. Since this directory is ignored by Watchman and other
   * tools, allow overriding this behavior for projects that need it
   * by specifying one or more environment variables which are checked
   * for a temporary directory to use.
   */
  public Optional<ImmutableList<String>> getTestTempDirEnvVars() {
    if (!getValue("test", "temp_dir_env_vars").isPresent()) {
      return Optional.absent();
    } else {
      return Optional.of(getListWithoutComments("test", "temp_dir_env_vars"));
    }
  }

  /**
   * Create an Ansi object appropriate for the current output. First respect the user's
   * preferences, if set. Next, respect any default provided by the caller. (This is used by buckd
   * to tell the daemon about the client's terminal.) Finally, allow the Ansi class to autodetect
   * whether the current output is a tty.
   * @param defaultColor Default value provided by the caller (e.g. the client of buckd)
   */
  public Ansi createAnsi(Optional<String> defaultColor) {
    String color = getValue("color", "ui").or(defaultColor).or("auto");

    switch (color) {
      case "false":
      case "never":
        return Ansi.withoutTty();
      case "true":
      case "always":
        return Ansi.forceTty();
      case "auto":
      default:
        return new Ansi(
            AnsiEnvironmentChecking.environmentSupportsAnsiEscapes(platform, environment));
    }
  }

  /**
   * @return the depth of a local build chain which should trigger skipping the cache.
   */
  public Optional<Long> getSkipLocalBuildChainDepth() {
    return getLong("cache", "skip_local_build_chain_depth");
  }

  @Nullable
  public Path resolvePathThatMayBeOutsideTheProjectFilesystem(@Nullable Path path) {
    if (path == null) {
      return path;
    }

    if (path.isAbsolute()) {
      return path;
    }

    Path expandedPath = MorePaths.expandHomeDir(path);
    return projectFilesystem.getAbsolutifier().apply(expandedPath);
  }

  public String getLocalhost() {
    try {
      return HostnameFetching.getHostname();
    } catch (IOException e) {
      return "<unknown>";
    }
  }

  public Optional<URI> getRemoteLogUrl() {
    return getValue("log", "remote_log_url").transform(TO_URI);
  }

  public Optional<Float> getRemoteLogSampleRate() {
    Optional<Float> sampleRate = config.getFloat("log", "remote_log_sample_rate");
    if (sampleRate.isPresent()) {
      if (sampleRate.get() > 1.0f) {
        throw new HumanReadableException(
            ".buckconfig: remote_log_sample_rate should be less than or equal to 1.0.");
      }
      if (sampleRate.get() < 0.0f) {
        throw new HumanReadableException(
            ".buckconfig: remote_log_sample_rate should be greater than or equal to 0.");
      }
    }
    return sampleRate;
 }

  public Optional<String> getValue(String sectionName, String propertyName) {
    return config.getValue(sectionName, propertyName);
  }

  public Optional<Long> getLong(String sectionName, String propertyName) {
    return config.getLong(sectionName, propertyName);
  }

  public boolean getBooleanValue(String sectionName, String propertyName, boolean defaultValue) {
    return config.getBooleanValue(sectionName, propertyName, defaultValue);
  }

  private <T> T required(String section, String field, Optional<T> value) {
    if (!value.isPresent()) {
      throw new HumanReadableException(String.format(
          ".buckconfig: %s:%s must be set",
          section,
          field));
    }
    return value.get();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (!(obj instanceof BuckConfig)) {
      return false;
    }
    BuckConfig that = (BuckConfig) obj;
    return Objects.equal(this.config, that.config);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(config);
  }

  public ImmutableMap<String, String> getEnvironment() {
    return environment;
  }

  public Platform getPlatform() {
    return platform;
  }

  public String[] getEnv(String propertyName, String separator) {
    String value = getEnvironment().get(propertyName);
    if (value == null) {
      value = "";
    }
    return value.split(separator);
  }

  /**
   * @return the mode with which to run the build engine.
   */
  public CachingBuildEngine.BuildMode getBuildEngineMode() {
    return getEnum("build", "engine", CachingBuildEngine.BuildMode.class)
        .or(CachingBuildEngine.BuildMode.SHALLOW);
  }

  /**
   * @return the mode with which to run the build engine.
   */
  public CachingBuildEngine.DepFiles getBuildDepFiles() {
    return getBooleanValue("build", "depfiles", true) ?
        CachingBuildEngine.DepFiles.ENABLED :
        CachingBuildEngine.DepFiles.DISABLED;
  }

  /**
   * @return the path for the given section and property.
   */
  public Optional<Path> getPath(String sectionName, String name) {
    return getPath(sectionName, name, true);
  }

  /**
   * @return the number of threads Buck should use.
   */
  public int getNumThreads() {
    return getNumThreads((int) (Runtime.getRuntime().availableProcessors() * 1.25));
  }

  /**
   * @return the number of threads Buck should use or the specified defaultValue if it is not set.
   */
  public int getNumThreads(int defaultValue) {
    return config.getLong("build", "threads")
        .or((long) defaultValue)
        .intValue();
  }

  /**
   * @return the maximum load limit that Buck should stay under on the system.
   */
  public float getLoadLimit() {
    return config.getFloat("build", "load_limit")
        .or(Float.POSITIVE_INFINITY);
  }

  public Optional<Path> getPath(String sectionName, String name, boolean isRepoRootRelative) {
    Optional<String> pathString = getValue(sectionName, name);
    return pathString.isPresent() ?
        isRepoRootRelative ?
            checkPathExists(
                pathString.get(),
                String.format("Overridden %s:%s path not found: ", sectionName, name)) :
            Optional.of(getPathFromVfs(pathString.get())) :
        Optional.<Path>absent();
  }

  /**
   * Return a {@link Path} from the underlying {@link java.nio.file.FileSystem} implementation. This
   * allows to safely call {@link Path#resolve(Path)} and similar calls without exceptions caused by
   * mis-matched underlying filesystem implementations causing grief. This is particularly useful
   * for those times where we're using (eg) JimFs for our testing.
   */
  private Path getPathFromVfs(String path, String... extra) {
    return projectFilesystem.getRootPath().getFileSystem().getPath(path, extra);
  }

  public Optional<Path> checkPathExists(String pathString, String errorMsg) {
    Path path = getPathFromVfs(pathString);
    if (projectFilesystem.exists(path)) {
      return Optional.of(projectFilesystem.getPathForRelativePath(path));
    }
    throw new HumanReadableException(errorMsg + path);
  }

  public ImmutableSet<String> getSections() {
    return config.getSectionToEntries().keySet();
  }

  public Optional<ImmutableList<String>> getExternalTestRunner() {
    Optional<String> value = getValue("test", "external_runner");
    if (!value.isPresent()) {
      return Optional.absent();
    }
    return Optional.of(ImmutableList.copyOf(Splitter.on(' ').splitToList(value.get())));
  }

}
