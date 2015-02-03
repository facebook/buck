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
import com.facebook.buck.event.ThrowableConsoleEvent;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CassandraArtifactCache;
import com.facebook.buck.rules.DirArtifactCache;
import com.facebook.buck.rules.HttpArtifactCache;
import com.facebook.buck.rules.MultiArtifactCache;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.HumanReadableException;
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
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
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
import java.util.Locale;
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

  private static final String DEFAULT_BUCK_CONFIG_FILE_NAME = ".buckconfig";
  public static final String DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME = ".buckconfig.local";

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
  private static final String DEFAULT_HTTP_CACHE_MODE = CacheMode.readwrite.name();
  private static final String DEFAULT_HTTP_CACHE_PORT = "8080";
  private static final String DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS = "10";
  private static final String DEFAULT_MAX_TRACES = "25";

  private final ImmutableMap<String, ImmutableMap<String, String>> sectionsToEntries;

  private final ImmutableMap<String, BuildTarget> aliasToBuildTargetMap;

  private final ImmutableMap<String, Path> repoNamesToPaths;

  private final ProjectFilesystem projectFilesystem;

  private final BuildTargetParser buildTargetParser;

  private final Platform platform;

  private final ImmutableMap<String, String> environment;

  private enum ArtifactCacheNames {
    dir,
    cassandra,
    http
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
  BuckConfig(
      Map<String, Map<String, String>> sectionsToEntries,
      ProjectFilesystem projectFilesystem,
      BuildTargetParser buildTargetParser,
      Platform platform,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, Path> repoNamesToPaths) {
    this.projectFilesystem = projectFilesystem;
    this.buildTargetParser = buildTargetParser;

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

    this.repoNamesToPaths = repoNamesToPaths;

    this.platform = platform;
    this.environment = environment;
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
    BuildTargetParser buildTargetParser = new BuildTargetParser();

    if (Iterables.isEmpty(files)) {
      return new BuckConfig(
          ImmutableMap.<String, Map<String, String>>of(),
          projectFilesystem,
          buildTargetParser,
          platform,
          environment,
          ImmutableMap.<String, Path>of());
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

  @VisibleForTesting
  static Map<String, Map<String, String>> createFromReaders(Iterable<Reader> readers)
      throws IOException {

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
          throw new HumanReadableException(
              "Duplicate definition for %s in the [%s] section of your .buckconfig or " +
                  ".buckconfig.local.",
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
    ImmutableMap<String, Path> repoNamesToPaths =
        createRepoNamesToPaths(projectFilesystem, sectionsToEntries);
    return new BuckConfig(
        sectionsToEntries,
        projectFilesystem,
        buildTargetParser,
        platform,
        environment,
        repoNamesToPaths);
  }

  public ImmutableMap<String, String> getEntriesForSection(String section) {
    ImmutableMap<String, String> entries = sectionsToEntries.get(section);
    if (entries != null) {
      return entries;
    } else {
      return ImmutableMap.of();
    }
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

    Path buckdDir = Paths.get(System.getProperty(BUCK_BUCKD_DIR_KEY, ".buckd"));
    Path cacheDir = getCacheDir();
    for (Path path : ImmutableList.of(buckdDir, cacheDir)) {
      if (!path.toString().isEmpty()) {
        builder.add(path);
      }
    }

    if (projectConfig.containsKey(ignoreKey)) {
      builder.addAll(
          Lists.transform(asListWithoutComments(projectConfig.get(ignoreKey)), MorePaths.TO_PATH));
    }

    // Normalize paths in order to eliminate trailing '/' characters and whatnot.
    return builder.build();
  }

  /**
   * ini4j leaves things that look like comments in the values of entries in the file. Generally,
   * we don't want to include these in our parameters, so filter them out where necessary. In an INI
   * file, the comment separator is ";", but some parsers (ini4j included) use "#" too. This method
   * handles both cases.
   *
   * @return An {@link ImmutableList} containing all entries that don't look like comments, or the
   *     empty list if there are no values of if {@code value} is null.
   */
  private ImmutableList<String> asListWithoutComments(@Nullable String value) {
    if (value == null) {
      return ImmutableList.of();
    }

    Iterable<String> allValues = Splitter.on(',')
        .omitEmptyStrings()
        .trimResults()
        .split(value);
    return FluentIterable.from(allValues)
        .filter(
            new Predicate<String>() {
              @Override
              public boolean apply(String input) {
                // Reject if the first printable character is an ini comment char (';' or '#')
                return !Pattern.compile("^\\s*[#;]").matcher(input).find();
              }
            })
        .toList();
  }

  public ImmutableList<String> asListWithoutComments(Optional<String> value) {
    return asListWithoutComments(value.orNull());
  }

  @Nullable
  public String getBuildTargetForAlias(String alias) {
    BuildTarget buildTarget = aliasToBuildTargetMap.get(alias);
    if (buildTarget != null) {
      return buildTarget.getFullyQualifiedName();
    } else {
      return null;
    }
  }

  public BuildTarget getBuildTargetForFullyQualifiedTarget(String target) {
    return buildTargetParser.parse(
        target,
        BuildTargetPatternParser.fullyQualified(buildTargetParser));
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
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.absent();
    }
    try {
      return Optional.of(Enum.valueOf(clazz, value.get().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException(
          ".buckconfig: %s:%s must be one of %s (was %s)",
          section,
          field,
          clazz.getEnumConstants(),
          value.get());
    }
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
      return Optional.<SourcePath>of(new PathSourcePath(Paths.get(value.get())));
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
   * In a {@link BuckConfig}, an alias can either refer to a fully-qualified build target, or an
   * alias defined earlier in the {@code alias} section. The mapping produced by this method
   * reflects the result of resolving all aliases as values in the {@code alias} section.
   */
  private static ImmutableMap<String, BuildTarget> createAliasToBuildTargetMap(
      ImmutableMap<String, String> rawAliasMap,
      BuildTargetParser buildTargetParser) {
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
        buildTarget = buildTargetParser.parse(
            value,
            BuildTargetPatternParser.fullyQualified(buildTargetParser));
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
    ImmutableMap<String, String> aliases = sectionsToEntries.get(ALIAS_SECTION_HEADER);
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

  public boolean isTreatingAssumptionsAsErrors() {
    return getBooleanValue("test", "assumptions-are-errors", false);
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
    return ImmutableSet.copyOf(asListWithoutComments(getValue("extensions", "listeners")));
  }

  public ImmutableSet<String> getSrcRoots() {
    return ImmutableSet.copyOf(asListWithoutComments(getValue("java", "src_roots")));
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
    return asListWithoutComments(excludedRulesOptional);
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
        return new Ansi(
            AnsiEnvironmentChecking.environmentSupportsAnsiEscapes(platform, environment));
    }
  }

  public ArtifactCache createArtifactCache(
      Optional<String> currentWifiSsid,
      BuckEventBus buckEventBus,
      FileHashCache fileHashCache) {
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
              buckEventBus,
              fileHashCache);
          if (cassandraArtifactCache != null) {
            builder.add(cassandraArtifactCache);
          }
          break;
        case http:
          ArtifactCache httpArtifactCache = createHttpArtifactCache(buckEventBus, fileHashCache);
          builder.add(httpArtifactCache);
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
    return asListWithoutComments(getValue("cache", "mode"));
  }

  @VisibleForTesting
  Path getCacheDir() {
    String cacheDir = getValue("cache", "dir").or(DEFAULT_CACHE_DIR);
    Path pathToCacheDir = resolvePathThatMayBeOutsideTheProjectFilesystem(Paths.get(cacheDir));
    return Preconditions.checkNotNull(pathToCacheDir);
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

  public Optional<Long> getCacheDirMaxSizeBytes() {
    return getValue("cache", "dir_max_size").transform(
        new Function<String, Long>() {
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
   * Clients should use {@link #createArtifactCache(Optional, BuckEventBus, FileHashCache)} unless
   * it is expected that the user has defined a {@code cassandra} cache, and that it should be used
   * exclusively.
   */
  @Nullable
  CassandraArtifactCache createCassandraArtifactCache(
      Optional<String> currentWifiSsid,
      BuckEventBus buckEventBus,
      FileHashCache fileHashCache) {
    // cache.blacklisted_wifi_ssids
    ImmutableSet<String> blacklistedWifi = ImmutableSet.copyOf(
        asListWithoutComments(getValue("cache", "blacklisted_wifi_ssids")));
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
      return new CassandraArtifactCache(
          cacheHosts,
          port,
          timeoutSeconds,
          doStore,
          buckEventBus,
          fileHashCache);
    } catch (ConnectionException e) {
      buckEventBus.post(ThrowableConsoleEvent.create(e, "Cassandra cache connection failure."));
      return null;
    }
  }

  private ArtifactCache createHttpArtifactCache(
      BuckEventBus buckEventBus,
      FileHashCache fileHashCache) {
    String host = getValue("cache", "http_host").or("localhost");
    int port = Integer.parseInt(getValue("cache", "http_port").or(DEFAULT_HTTP_CACHE_PORT));
    int timeoutSeconds = Integer.parseInt(
        getValue("cache", "http_timeout_seconds").or(DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS));
    boolean doStore = readCacheMode("http_mode", DEFAULT_HTTP_CACHE_MODE);
    return new HttpArtifactCache(
        host,
        port,
        timeoutSeconds,
        doStore,
        projectFilesystem,
        buckEventBus,
        fileHashCache);
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

  public Optional<Long> getLong(String sectionName, String propertyName) {
    Optional<String> value = getValue(sectionName, propertyName);
    return value.isPresent() ?
        Optional.of(Long.valueOf(value.get())) :
        Optional.<Long>absent();
  }

  public boolean getBooleanValue(String sectionName, String propertyName, boolean defaultValue) {
    Map<String, String> entries = getEntriesForSection(sectionName);
    if (!entries.containsKey(propertyName)) {
      return defaultValue;
    }

    String answer = Preconditions.checkNotNull(entries.get(propertyName));
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
    return Objects.equal(this.sectionsToEntries, that.sectionsToEntries);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(sectionsToEntries);
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

  /**
   * Returns the path to the platform specific aapt executable that is overridden by the current
   * project. If not specified, the Android platform aapt will be used.
   */
  public Optional<Path> getAaptOverride() {
    Optional<String> pathString = getValue("tools", "aapt");
    if (!pathString.isPresent()) {
      return Optional.absent();
    }

    String platformDir;
    if (platform == Platform.LINUX) {
      platformDir = "linux";
    } else if (platform == Platform.MACOS) {
      platformDir = "mac";
    } else if (platform == Platform.WINDOWS) {
      platformDir = "windows";
    } else {
      return Optional.absent();
    }

    Path pathToAapt = Paths.get(pathString.get(), platformDir, "aapt");
    return checkPathExists(pathToAapt.toString(), "Overridden aapt path not found: ");
  }

  /**
   * @return the path for the given section and property.
   */
  public Optional<Path> getPath(String sectionName, String name) {
    Optional<String> pathString = getValue(sectionName, name);
    return pathString.isPresent() ?
        checkPathExists(
            pathString.get(),
            String.format("Overridden %s:%s path not found: ", sectionName, name)) :
        Optional.<Path>absent();
  }

  public Optional<Path> checkPathExists(String pathString, String errorMsg) {
    Path path = Paths.get(pathString);
    if (projectFilesystem.exists(path)) {
      return Optional.of(projectFilesystem.resolve(path));
    }
    throw new HumanReadableException(errorMsg + path);
  }

  private static ImmutableMap<String, Path> createRepoNamesToPaths(
      ProjectFilesystem filesystem,
      Map<String, Map<String, String>> sectionsToEntries)
      throws IOException {
    @Nullable Map<String, String> repositoryConfigs = sectionsToEntries.get("repositories");
    if (repositoryConfigs == null) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, Path> repositoryPaths = ImmutableMap.builder();
    for (String name : repositoryConfigs.keySet()) {
      String pathString = repositoryConfigs.get(name);
      Path canonicalPath = filesystem.resolve(Paths.get(pathString)).toRealPath();
      repositoryPaths.put(name, canonicalPath);
    }
    return repositoryPaths.build();
  }

  public ImmutableMap<String, Path> getRepositoryPaths() {
    return repoNamesToPaths;
  }

  @VisibleForTesting
  String getProperty(String key, String def) {
    return System.getProperty(key, def);
  }

  /**
   * @param projectFilesystem The directory that is the root of the project being built.
   */
  public static BuckConfig createDefaultBuckConfig(
      ProjectFilesystem projectFilesystem,
      Platform platform,
      ImmutableMap<String, String> environment)
      throws IOException {
    ImmutableList.Builder<File> configFileBuilder = ImmutableList.builder();
    File configFile = projectFilesystem.getFileForRelativePath(DEFAULT_BUCK_CONFIG_FILE_NAME);
    if (configFile.isFile()) {
      configFileBuilder.add(configFile);
    }
    File overrideConfigFile = projectFilesystem.getFileForRelativePath(
        DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME);
    if (overrideConfigFile.isFile()) {
      configFileBuilder.add(overrideConfigFile);
    }

    ImmutableList<File> configFiles = configFileBuilder.build();
    return BuckConfig.createFromFiles(
        projectFilesystem,
        configFiles,
        platform,
        environment);
  }
}
