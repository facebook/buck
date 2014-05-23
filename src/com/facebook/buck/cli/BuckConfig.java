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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ThrowableLogEvent;
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.CassandraArtifactCache;
import com.facebook.buck.rules.DirArtifactCache;
import com.facebook.buck.rules.MultiArtifactCache;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;

import org.ini4j.Ini;
import org.ini4j.Profile.Section;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
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
  private static final String ALIAS_SECTION_HEADER = "alias";

  /**
   * This pattern is designed so that a fully-qualified build target cannot be a valid alias name
   * and vice-versa.
   */
  private static final Pattern ALIAS_PATTERN = Pattern.compile("[a-zA-Z_-][a-zA-Z0-9_-]*");

  @VisibleForTesting
  static final String BUCK_BUCKD_DIR_KEY = "buck.buckd_dir";

  private static final String DEFAULT_CACHE_DIR = "buck-cache";
  private static final String DEFAULT_DIR_CACHE_MODE = CacheMode.readwrite.name();
  private static final String DEFAULT_CASSANDRA_PORT = "9160";
  private static final String DEFAULT_CASSANDRA_MODE = CacheMode.readwrite.name();
  private static final String DEFAULT_CASSANDRA_TIMEOUT_SECONDS = "10";
  private static final String DEFAULT_MAX_TRACES = "25";

  // Prefer "python2" where available (Linux), but fall back to "python" (Mac).
  private static final ImmutableList<String> PYTHON_INTERPRETER_NAMES =
      ImmutableList.of("python2", "python");

  private final ImmutableMap<String, ImmutableMap<String, String>> sectionsToEntries;

  private final ImmutableMap<String, BuildTarget> aliasToBuildTargetMap;

  private final ProjectFilesystem projectFilesystem;

  private final BuildTargetParser buildTargetParser;

  private final Platform platform;

  private final ImmutableMap<String, String> environment;

  private enum ArtifactCacheNames {
    dir,
    cassandra
  }

  private enum CacheMode {
    readonly(false),
    readwrite(true),
    ;

    private final boolean doStore;

    private CacheMode(boolean doStore) {
      this.doStore = doStore;
    }
  }

  @VisibleForTesting
  BuckConfig(Map<String, Map<String, String>> sectionsToEntries,
      ProjectFilesystem projectFilesystem,
      BuildTargetParser buildTargetParser,
      Platform platform,
      ImmutableMap<String, String> environment) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.buildTargetParser = Preconditions.checkNotNull(buildTargetParser);

    Preconditions.checkNotNull(sectionsToEntries);
    ImmutableMap.Builder<String, ImmutableMap<String, String>> sectionsToEntriesBuilder =
        ImmutableMap.builder();
    for (Map.Entry<String, Map<String, String>> entry : sectionsToEntries.entrySet()) {
      sectionsToEntriesBuilder.put(entry.getKey(), ImmutableMap.copyOf(entry.getValue()));
    }
    this.sectionsToEntries = sectionsToEntriesBuilder.build();

    // We could create this Map on demand; however, in practice, it is almost always needed when
    // BuckConfig is needed because CommandLineBuildTargetNormalizer needs it.
    this.aliasToBuildTargetMap = createAliasToBuildTargetMap(
        this.getEntriesForSection(ALIAS_SECTION_HEADER),
        buildTargetParser);

    this.platform = Preconditions.checkNotNull(platform);
    this.environment = Preconditions.checkNotNull(environment);
  }

  /**
   * Takes a sequence of {@code .buckconfig} files and loads them, in order, to create a
   * {@code BuckConfig} object. Each successive file that is loaded has the ability to override
   * definitions from a previous file.
   * @param projectFilesystem project for which the {@link BuckConfig} is being created.
   * @param files The sequence of {@code .buckconfig} files to load.
   */
  public static BuckConfig createFromFiles(ProjectFilesystem projectFilesystem,
      Iterable<File> files,
      Platform platform,
      ImmutableMap<String, String> environment)
      throws IOException {
    Preconditions.checkNotNull(projectFilesystem);
    Preconditions.checkNotNull(files);
    BuildTargetParser buildTargetParser = new BuildTargetParser(projectFilesystem);

    if (Iterables.isEmpty(files)) {
      return new BuckConfig(
          ImmutableMap.<String, Map<String, String>>of(),
          projectFilesystem,
          buildTargetParser,
          platform,
          environment);
    }

    // Convert the Files to Readers.
    ImmutableList.Builder<Reader> readers = ImmutableList.builder();
    for (File file : files) {
      readers.add(Files.newReader(file, Charsets.UTF_8));
    }
    return createFromReaders(
        readers.build(),
        projectFilesystem,
        buildTargetParser,
        platform,
        environment);
  }

  /**
   * @return whether {@code aliasName} conforms to the pattern for a valid alias name. This does not
   *     indicate whether it is an alias that maps to a build target in a BuckConfig.
   */
  private static boolean isValidAliasName(String aliasName) {
    return aliasName != null && ALIAS_PATTERN.matcher(aliasName).matches();
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

    if (aliasName == null) {
      throw new HumanReadableException("%s cannot be null.", fieldName);
    }

    if (aliasName.isEmpty()) {
      throw new HumanReadableException("%s cannot be the empty string.", fieldName);
    }

    throw new HumanReadableException("Not a valid %s: %s.", fieldName.toLowerCase(), aliasName);
  }

  @VisibleForTesting
  public static BuckConfig createFromReader(
      Reader reader,
      ProjectFilesystem projectFilesystem,
      BuildTargetParser buildTargetParser,
      Platform platform,
      ImmutableMap<String, String> environment)
      throws IOException {
    return createFromReaders(
        ImmutableList.of(reader),
        projectFilesystem,
        buildTargetParser,
        platform,
        environment);
  }

  @VisibleForTesting
  static Map<String, Map<String, String>> createFromReaders(Iterable<Reader> readers)
      throws IOException {
    Preconditions.checkNotNull(readers);

    Ini ini = new Ini();
    for (Reader reader : readers) {
      // The data contained by reader need to be processed twice (first during validation, then
      // when merging into ini), so read the data into a string that can be used as the source of
      // two StringReaders.
      try (Reader r = reader) {
        String iniString = CharStreams.toString(r);
        validateReader(new StringReader(iniString));
        ini.load(new StringReader(iniString));
      }
    }

    Map<String, Map<String, String>> sectionsToEntries = Maps.newHashMap();
    for (String sectionName : ini.keySet()) {
      ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
      Section section = ini.get(sectionName);
      for (String propertyName : section.keySet()) {
        String propertyValue = section.get(propertyName);
        builder.put(propertyName, propertyValue);
      }

      ImmutableMap<String, String> sectionToEntries = builder.build();
      sectionsToEntries.put(sectionName, sectionToEntries);
    }

    return sectionsToEntries;
  }

  private static void validateReader(Reader reader) throws IOException {
    // Verify that within each ini file, no section has the same key specified more than once.
    Ini ini = new Ini();
    ini.load(reader);
    for (String sectionName : ini.keySet()) {
      Section section = ini.get(sectionName);
      for (String propertyName : section.keySet()) {
        if (section.getAll(propertyName).size() > 1) {
          throw new HumanReadableException("Duplicate definition for %s in [%s].",
              propertyName,
              sectionName);
        }
      }
    }
  }

  @VisibleForTesting
  static BuckConfig createFromReaders(Iterable<Reader> readers,
      ProjectFilesystem projectFilesystem,
      BuildTargetParser buildTargetParser,
      Platform platform,
      ImmutableMap<String, String> environment)
      throws IOException {
    Map<String, Map<String, String>> sectionsToEntries = createFromReaders(readers);
    return new BuckConfig(
        sectionsToEntries,
        projectFilesystem,
        buildTargetParser,
        platform,
        environment);
  }

  public ImmutableMap<String, String> getEntriesForSection(String section) {
    Preconditions.checkNotNull(section);
    ImmutableMap<String, String> entries = sectionsToEntries.get(section);
    if (entries != null) {
      return entries;
    } else {
      return ImmutableMap.of();
    }
  }

  /**
   * A (possibly empty) sequence of paths to files that should be included by default when
   * evaluating a build file.
   */
  public Iterable<String> getDefaultIncludes() {
    ImmutableMap<String, String> entries = getEntriesForSection("buildfile");
    String includes = Strings.nullToEmpty(entries.get("includes"));
    return Splitter.on(' ').trimResults().omitEmptyStrings().split(includes);
  }

  /**
   * A set of paths to subtrees that do not contain source files, build files or files that could
   * affect either (buck-out, .idea, .buckd, buck-cache, .git, etc.).  May return absolute paths
   * as well as relative paths.
   */
  public ImmutableSet<Path> getIgnorePaths() {
    final ImmutableMap<String, String> projectConfig = getEntriesForSection("project");
    final String ignoreKey = "ignore";
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();

    builder.add(Paths.get(BuckConstant.BUCK_OUTPUT_DIRECTORY));
    builder.add(Paths.get(".idea"));

    // Take care not to ignore absolute paths.
    Path buckdDir = Paths.get(System.getProperty(BUCK_BUCKD_DIR_KEY, ".buckd"));
    Path cacheDir = getCacheDir();
    for (Path path : ImmutableList.of(buckdDir, cacheDir)) {
      if (!path.toString().isEmpty()) {
        builder.add(path);
      }
    }

    if (projectConfig.containsKey(ignoreKey)) {
      builder.addAll(MorePaths.asPaths(
          Splitter.on(',')
            .omitEmptyStrings()
            .trimResults()
            .split(projectConfig.get(ignoreKey))));
    }

    // Normalize paths in order to eliminate trailing '/' characters and whatnot.
    return builder.build();
  }

  public ImmutableSet<Pattern> getTempFilePatterns() {
    final ImmutableMap<String, String> projectConfig = getEntriesForSection("project");
    final String tempFilesKey = "temp_files";
    ImmutableSet.Builder<Pattern> builder = ImmutableSet.builder();
    if (projectConfig.containsKey(tempFilesKey)) {
      for (String regex : Splitter.on(',')
          .omitEmptyStrings()
          .trimResults()
          .split(projectConfig.get(tempFilesKey))) {
        builder.add(Pattern.compile(regex));
      }
    }
    return builder.build();
  }

  @Nullable
  public String getBuildTargetForAlias(String alias) {
    Preconditions.checkNotNull(alias);
    BuildTarget buildTarget = aliasToBuildTargetMap.get(alias);
    if (buildTarget != null) {
      return buildTarget.getFullyQualifiedName();
    } else {
      return null;
    }
  }

  public BuildTarget getBuildTargetForFullyQualifiedTarget(String target)
      throws NoSuchBuildTargetException {
    return buildTargetParser.parse(target, ParseContext.fullyQualified());
  }

  /**
   * In a {@link BuckConfig}, an alias can either refer to a fully-qualified build target, or an
   * alias defined earlier in the {@code alias} section. The mapping produced by this method
   * reflects the result of resolving all aliases as values in the {@code alias} section.
   */
  private static ImmutableMap<String, BuildTarget> createAliasToBuildTargetMap(
      ImmutableMap<String, String> rawAliasMap, BuildTargetParser buildTargetParser) {
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
        try {
          buildTarget = buildTargetParser.parse(value, ParseContext.fullyQualified());
        } catch (NoSuchBuildTargetException e) {
          throw new HumanReadableException(e);
        }
      }
      aliasToBuildTarget.put(alias, buildTarget);
    }
    return ImmutableMap.copyOf(aliasToBuildTarget);
  }

  /**
   * Create a map of {@link BuildTarget} base paths to aliases. Note that there may be more than
   * one alias to a base path, so the first one listed in the .buckconfig will be chosen.
   */
  public ImmutableMap<String, String> getBasePathToAliasMap() {
    ImmutableMap<String, String> aliases = sectionsToEntries.get(ALIAS_SECTION_HEADER);
    if (aliases == null) {
      return ImmutableMap.of();
    }

    // Build up the Map with an ordinary HashMap because we need to be able to check whether the Map
    // already contains the key before inserting.
    Map<String, String> basePathToAlias = Maps.newHashMap();
    for (Map.Entry<String, BuildTarget> entry : aliasToBuildTargetMap.entrySet()) {
      String alias = entry.getKey();
      BuildTarget buildTarget = entry.getValue();

      String basePath = buildTarget.getBasePath();
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

  public boolean getRestartAdbOnFailure() {
    return Boolean.parseBoolean(getValue("adb", "adb_restart_on_failure").or("true"));
  }

  public boolean getFlushEventsBeforeExit() {
    return getBooleanValue("daemon", "flush_events_before_exit", false);
  }

  public ImmutableSet<String> getListenerJars() {
    String jarPathsString = getValue("extensions", "listeners").or("");
    Splitter splitter = Splitter.on(',').omitEmptyStrings().trimResults();
    return ImmutableSet.copyOf(splitter.split(jarPathsString));
  }

  public ImmutableSet<String> getSrcRoots() {
    Optional<String> srcRootsOptional = getValue("java", "src_roots");
    if (srcRootsOptional.isPresent()) {
      String srcRoots = srcRootsOptional.get();
      Splitter splitter = Splitter.on(',').omitEmptyStrings().trimResults();
      return ImmutableSet.copyOf(splitter.split(srcRoots));
    } else {
      return ImmutableSet.of();
    }
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
    Optional<String> excludedRulesOptional = getValue("test", "excluded_labels");
    if (excludedRulesOptional.isPresent()) {
      String excludedRules = excludedRulesOptional.get();
      Splitter splitter = Splitter.on(',').omitEmptyStrings().trimResults();
      ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
      // Validate that all specified labels are valid.
      for (String raw : splitter.split(excludedRules)) {
        builder.add(raw);
      }
      return builder.build();
    } else {
      return ImmutableList.of();
    }
  }

  @Beta
  Optional<BuildDependencies> getBuildDependencies() {
    Optional<String> buildDependenciesOptional = getValue("build", "build_dependencies");
    if (buildDependenciesOptional.isPresent()) {
      try {
        return Optional.of(BuildDependencies.valueOf(buildDependenciesOptional.get()));
      } catch (IllegalArgumentException e) {
        throw new HumanReadableException(
            "%s is not a valid value for build_dependencies.  Must be one of: %s",
            buildDependenciesOptional.get(),
            Joiner.on(", ").join(BuildDependencies.values()));
      }
    } else {
      return Optional.absent();
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
        return new Ansi(platform);
    }
  }

  public ArtifactCache createArtifactCache(
      Optional<String> currentWifiSsid,
      BuckEventBus buckEventBus) {
    ImmutableList<String> modes = getArtifactCacheModes();
    if (modes.isEmpty()) {
      return new NoopArtifactCache();
    }
    ImmutableList.Builder<ArtifactCache> builder = ImmutableList.builder();
    try {
      for (String mode : modes) {
        switch (ArtifactCacheNames.valueOf(mode)) {
        case dir:
          ArtifactCache dirArtifactCache = createDirArtifactCache();
          buckEventBus.register(dirArtifactCache);
          builder.add(dirArtifactCache);
          break;
        case cassandra:
          ArtifactCache cassandraArtifactCache = createCassandraArtifactCache(
              currentWifiSsid,
              buckEventBus);
          if (cassandraArtifactCache != null) {
            builder.add(cassandraArtifactCache);
          }
          break;
        }
      }
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException("Unusable cache.mode: '%s'", modes.toString());
    }
    ImmutableList<ArtifactCache> artifactCaches = builder.build();
    if (artifactCaches.size() == 1) {
      // Don't bother wrapping a single artifact cache in MultiArtifactCache.
      return artifactCaches.get(0);
    } else {
      return new MultiArtifactCache(artifactCaches);
    }
  }

  ImmutableList<String> getArtifactCacheModes() {
    String cacheMode = getValue("cache", "mode").or("");
    return ImmutableList.copyOf(Splitter.on(',').trimResults().omitEmptyStrings().split(cacheMode));
  }

  @VisibleForTesting
  Path getCacheDir() {
    String cacheDir = getValue("cache", "dir").or(DEFAULT_CACHE_DIR);
    if (!cacheDir.isEmpty() && cacheDir.charAt(0) == '/') {
      return Paths.get(cacheDir);
    }
    return projectFilesystem.getAbsolutifier().apply(Paths.get(cacheDir));
  }

  public Optional<Long> getCacheDirMaxSizeBytes() {
    return getValue("cache", "dir_max_size").transform(new Function<String, Long>() {
      @Override
      public Long apply(String input) {
        return SizeUnit.parseBytes(input);
      }
    });
  }

  private ArtifactCache createDirArtifactCache() {
    Path cacheDir = getCacheDir();
    File dir = cacheDir.toFile();
    boolean doStore = readCacheMode("dir_mode", DEFAULT_DIR_CACHE_MODE);
    try {
      return new DirArtifactCache(dir, doStore, getCacheDirMaxSizeBytes());
    } catch (IOException e) {
      throw new HumanReadableException("Failure initializing artifact cache directory: %s", dir);
    }
  }

  /**
   * Clients should use {@link #createArtifactCache(Optional, BuckEventBus)} unless it
   * is expected that the user has defined a {@code cassandra} cache, and that it should be used
   * exclusively.
   */
  @Nullable
  CassandraArtifactCache createCassandraArtifactCache(
      Optional<String> currentWifiSsid,
      BuckEventBus buckEventBus) {
    // cache.blacklisted_wifi_ssids
    ImmutableSet<String> blacklistedWifi = ImmutableSet.copyOf(
        Splitter.on(",")
            .trimResults()
            .omitEmptyStrings()
            .split(getValue("cache", "blacklisted_wifi_ssids").or("")));
    if (currentWifiSsid.isPresent() && blacklistedWifi.contains(currentWifiSsid.get())) {
      // We're connected to a wifi hotspot that has been explicitly blacklisted from connecting to
      // Cassandra.
      return null;
    }

    // cache.cassandra_mode
    final boolean doStore = readCacheMode("cassandra_mode", DEFAULT_CASSANDRA_MODE);
    // cache.hosts
    String cacheHosts = getValue("cache", "hosts").or("");
    // cache.port
    int port = Integer.parseInt(getValue("cache", "port").or(DEFAULT_CASSANDRA_PORT));
    // cache.connection_timeout_seconds
    int timeoutSeconds = Integer.parseInt(
        getValue("cache", "connection_timeout_seconds").or(DEFAULT_CASSANDRA_TIMEOUT_SECONDS));

    try {
      return new CassandraArtifactCache(cacheHosts, port, timeoutSeconds, doStore, buckEventBus);
    } catch (ConnectionException e) {
      buckEventBus.post(ThrowableLogEvent.create(e, "Cassandra cache connection failure."));
      return null;
    }
  }

  private boolean readCacheMode(String fieldName, String defaultValue) {
    String cacheMode = getValue("cache", fieldName).or(defaultValue);
    final boolean doStore;
    try {
      doStore = CacheMode.valueOf(cacheMode).doStore;
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException("Unusable cache.%s: '%s'", fieldName, cacheMode);
    }
    return doStore;
  }

  public Optional<String> getNdkVersion() {
    return getValue("ndk", "ndk_version");
  }

  public Optional<String> getValue(String sectionName, String propertyName) {
    ImmutableMap<String, String> properties = this.getEntriesForSection(sectionName);
    return Optional.fromNullable(properties.get(propertyName));
  }


  public boolean getBooleanValue(String sectionName, String propertyName, boolean defaultValue) {
    Map<String, String> entries = getEntriesForSection(sectionName);
    if (!entries.containsKey(propertyName)) {
      return defaultValue;
    }

    String answer = entries.get(propertyName);
    switch (answer.toLowerCase()) {
      case "yes":
      case "true":
        return true;

      case "no":
      case "false":
        return false;

      default:
        throw new HumanReadableException(
            "Unknown value for %s in [%s]: %s; should be yes/no true/false!",
            propertyName,
            sectionName);
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (!(obj instanceof BuckConfig)) {
      return false;
    }
    BuckConfig that = (BuckConfig) obj;
    return Objects.equal(this.sectionsToEntries, that.sectionsToEntries);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(sectionsToEntries);
  }

  @VisibleForTesting
  String[] getEnv(String propertyName, String separator) {
    String value = environment.get(propertyName);
    if (value == null) {
      value = "";
    }
    return value.split(separator);
  }

  /**
   * @return true if file is executable and not a directory.
   */
  private boolean isExecutableFile(File file) {
    return file.canExecute() && !file.isDirectory();
  }

  /**
   * Returns the path to python interpreter. If python is specified in the tools section
   * that is used and an error reported if invalid. If no python is specified, the PATH
   * is searched and if no python is found, jython is used as a fallback.
   * @return The found python interpreter.
   */
  public String getPythonInterpreter() {
    Optional<String> configPath = getValue("tools", "python");
    if (configPath.isPresent()) {
      // Python path in config. Use it or report error if invalid.
      File python = new File(configPath.get());
      if (isExecutableFile(python)) {
        return python.getAbsolutePath();
      }
      throw new HumanReadableException("Not a python executable: " + configPath.get());
    } else {
      for (String interpreterName : PYTHON_INTERPRETER_NAMES) {
        // For each path in PATH, test "python" with each PATHEXT suffix to allow
        // for file extensions.
        for (String path : getEnv("PATH", File.pathSeparator)) {
          for (String pathExt : getEnv("PATHEXT", File.pathSeparator)) {
            File python = new File(path, interpreterName + pathExt);
            if (isExecutableFile(python)) {
              return python.getAbsolutePath();
            }
          }
        }
      }
      // Fall back to Jython if no python found.
      File jython = new File(getProperty("buck.path_to_python_interp", "bin/jython"));
      if (isExecutableFile(jython)) {
        return jython.getAbsolutePath();
      }
      throw new HumanReadableException("No python or jython found.");
    }
  }

  /**
   * Returns the path to the proguard.jar file that is overridden by the current project.  If
   * not specified, the Android platform proguard.jar will be used.
   */
  public Optional<Path> getProguardJarOverride() {
    Optional<String> pathString = getValue("tools", "proguard");
    if (pathString.isPresent()) {
      return checkPathExists(pathString.get(), "Overridden proguard path not found: ");
    }
    return Optional.absent();
  }

  /**
   * Returns the path to the aapt executable that is overridden by the current project. If not
   * specified, the Android platform aapt will be used.
   */
  public Optional<Path> getAaptOverride() {
    Optional<String> pathString = getValue("tools", "aapt");
    if (pathString.isPresent()) {
      return checkPathExists(pathString.get(), "Overridden aapt path not found: ");
    }
    return Optional.absent();
  }

  public Optional<Path> checkPathExists(String pathString, String errorMsg) {
    Path path = Paths.get(pathString);
    if (projectFilesystem.exists(path)) {
      return Optional.of(projectFilesystem.resolve(path));
    }
    throw new HumanReadableException(errorMsg + path);
  }

  @VisibleForTesting
  String getProperty(String key, String def) {
    return System.getProperty(key, def);
  }
}
