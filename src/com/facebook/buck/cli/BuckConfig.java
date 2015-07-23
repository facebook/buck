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
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.DirArtifactCache;
import com.facebook.buck.rules.HttpArtifactCache;
import com.facebook.buck.rules.MultiArtifactCache;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Config;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Inis;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Response;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

  private static final String DEFAULT_BUCK_CONFIG_FILE_NAME = ".buckconfig";
  public static final String DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME = ".buckconfig.local";
  private static final String DEFAULT_BUCK_CONFIG_DIRECTORY_NAME = ".buckconfig.d";

  private static final Path GLOBAL_BUCK_CONFIG_FILE_PATH = Paths.get("/etc/buckconfig");
  private static final Path GLOBAL_BUCK_CONFIG_DIRECTORY_PATH = Paths.get("/etc/buckconfig.d");

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
  private static final String DEFAULT_MAX_TRACES = "25";

  private static final String DEFAULT_HTTP_URL = "http://localhost:8080";
  private static final String DEFAULT_HTTP_CACHE_MODE = CacheMode.readwrite.name();
  private static final String DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS = "3";

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

  private enum ArtifactCacheNames {
    dir,
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
   * Takes a sequence of {@code .buckconfig} files and loads them, in order, to create a
   * {@code BuckConfig} object. Each successive file that is loaded has the ability to override
   * definitions from a previous file.
   * @param projectFilesystem project for which the {@link BuckConfig} is being created.
   * @param files The sequence of {@code .buckconfig} files to load.
   */
  @SafeVarargs
  public static BuckConfig createFromFiles(
      ProjectFilesystem projectFilesystem,
      Iterable<File> files,
      Platform platform,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, ImmutableMap<String, String>>... configOverrides)
      throws IOException {
    if (Iterables.isEmpty(files)) {
      return new BuckConfig(
          new Config(configOverrides),
          projectFilesystem,
          platform,
          environment);
    }

    // Convert the Files to Readers.
    ImmutableMap.Builder<Path, Reader> readers = ImmutableMap.builder();
    for (File file : files) {
      readers.put(file.toPath(), Files.newReader(file, Charsets.UTF_8));
    }
    return createFromReaders(
        readers.build(),
        projectFilesystem,
        platform,
        environment,
        configOverrides);
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

  /**
   * A set of paths to subtrees that do not contain source files, build files or files that could
   * affect either (buck-out, .idea, .buckd, buck-cache, .git, etc.).  May return absolute paths
   * as well as relative paths.
   */
  public ImmutableSet<Path> getIgnorePaths() {
    final String projectKey = "project";
    final String ignoreKey = "ignore";
    final ImmutableMap<String, String> projectConfig = getEntriesForSection(projectKey);
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
          Lists.transform(getListWithoutComments(projectKey, ignoreKey), MorePaths.TO_PATH));
    }

    // Normalize paths in order to eliminate trailing '/' characters and whatnot.
    return builder.build();
  }

  public ImmutableList<String> getListWithoutComments(String section, String field) {
    return config.getListWithoutComments(section, field);
  }

  @Nullable
  public String getBuildTargetForAlias(String possiblyFlavoredAlias) {
    String alias = possiblyFlavoredAlias;
    int poundIdx = possiblyFlavoredAlias.indexOf('#');
    if (poundIdx != -1) {
      alias = possiblyFlavoredAlias.substring(0, poundIdx);
    }

    BuildTarget buildTarget = aliasToBuildTargetMap.get(alias);
    if (buildTarget != null) {
      return buildTarget.getFullyQualifiedName() +
          (poundIdx == -1 ? "" : possiblyFlavoredAlias.substring(poundIdx));
    } else {
      return null;
    }
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
      return Optional.<SourcePath>of(new PathSourcePath(projectFilesystem, Paths.get(value.get())));
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
      BuckEventBus buckEventBus) {
    ImmutableList<String> modes = getArtifactCacheModes();
    if (modes.isEmpty()) {
      return new NoopArtifactCache();
    }
    ImmutableList.Builder<ArtifactCache> builder = ImmutableList.builder();
    boolean useDistributedCache = isWifiUsableForDistributedCache(currentWifiSsid);
    try {
      for (String mode : modes) {
        switch (ArtifactCacheNames.valueOf(mode)) {
        case dir:
          ArtifactCache dirArtifactCache = createDirArtifactCache();
          buckEventBus.register(dirArtifactCache);
          builder.add(dirArtifactCache);
          break;
        case http:
          if (useDistributedCache) {
            ArtifactCache httpArtifactCache = createHttpArtifactCache(buckEventBus);
            builder.add(httpArtifactCache);
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
    return getListWithoutComments("cache", "mode");
  }

  /**
   * @return the depth of a local build chain which should trigger skipping the cache.
   */
  public Optional<Long> getSkipLocalBuildChainDepth() {
    return getLong("cache", "skip_local_build_chain_depth");
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
    boolean doStore = readCacheMode("dir_mode", DEFAULT_DIR_CACHE_MODE);
    try {
      return new DirArtifactCache(
          "dir",
          projectFilesystem,
          cacheDir,
          doStore,
          getCacheDirMaxSizeBytes());
    } catch (IOException e) {
      throw new HumanReadableException(
          "Failure initializing artifact cache directory: %s",
          cacheDir);
    }
  }

  @VisibleForTesting
  boolean isWifiUsableForDistributedCache(Optional<String> currentWifiSsid) {
    // cache.blacklisted_wifi_ssids
    ImmutableSet<String> blacklistedWifi = ImmutableSet.copyOf(
        getListWithoutComments("cache", "blacklisted_wifi_ssids"));
    if (currentWifiSsid.isPresent() && blacklistedWifi.contains(currentWifiSsid.get())) {
      // We're connected to a wifi hotspot that has been explicitly blacklisted from connecting to
      // a distributed cache.
      return false;
    }
    return true;
  }

  private String getLocalhost() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "<unknown>";
    }
  }

  private ArtifactCache createHttpArtifactCache(BuckEventBus buckEventBus) {
    URL url;
    try {
      url = new URL(getValue("cache", "http_url").or(DEFAULT_HTTP_URL));
    } catch (MalformedURLException e) {
      throw new HumanReadableException(e, "Malformed [cache]http_url: %s", e.getMessage());
    }

    int timeoutSeconds = Integer.parseInt(
        getValue("cache", "http_timeout_seconds").or(DEFAULT_HTTP_CACHE_TIMEOUT_SECONDS));

    boolean doStore = readCacheMode("http_mode", DEFAULT_HTTP_CACHE_MODE);

    // Setup the defaut client to use.
    OkHttpClient client = new OkHttpClient();
    final String localhost = getLocalhost();
    client.networkInterceptors().add(
        new Interceptor() {
          @Override
          public Response intercept(Chain chain) throws IOException {
            return chain.proceed(
                chain.request().newBuilder()
                    .addHeader("X-BuckCache-User", System.getProperty("user.name", "<unknown>"))
                    .addHeader("X-BuckCache-Host", localhost)
                    .build());
          }
        });
    client.setConnectTimeout(timeoutSeconds, TimeUnit.SECONDS);
    client.setConnectionPool(
        new ConnectionPool(
            // It's important that this number is greater than the `-j` parallelism,
            // as if it's too small, we'll overflow the reusable connection pool and
            // start spamming new connections.  While this isn't the best location,
            // the other current option is setting this wherever we construct a `Build`
            // object and have access to the `-j` argument.  However, since that is
            // created in several places leave it here for now.
            /* maxIdleConnections */ 200,
            /* keepAliveDurationMs */ TimeUnit.MINUTES.toMillis(5)));

    // For fetches, use a client with a read timeout.
    OkHttpClient fetchClient = client.clone();
    fetchClient.setReadTimeout(timeoutSeconds, TimeUnit.SECONDS);

    return new HttpArtifactCache(
        "http",
        fetchClient,
        client,
        url,
        doStore,
        projectFilesystem,
        buckEventBus,
        Hashing.crc32());
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

  public Optional<URI> getRemoteLogUrl() {
    return getValue("log", "remote_log_url").transform(TO_URI);
  }

  public Optional<String> getValue(String sectionName, String propertyName) {
    return config.getValue(sectionName, propertyName);
  }

  public Optional<Long> getLong(String sectionName, String propertyName) {
    return  config.getLong(sectionName, propertyName);
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
   * @return the path for the given section and property.
   */
  public Optional<Path> getPath(String sectionName, String name) {
    return getPath(sectionName, name, true);
  }

  public Optional<Path> getPath(String sectionName, String name, boolean isRepoRootRelative) {
    Optional<String> pathString = getValue(sectionName, name);
    return pathString.isPresent() ?
        isRepoRootRelative ?
            checkPathExists(
                pathString.get(),
                String.format("Overridden %s:%s path not found: ", sectionName, name)) :
            Optional.of(Paths.get(pathString.get())) :
        Optional.<Path>absent();
  }

  public Optional<Path> checkPathExists(String pathString, String errorMsg) {
    Path path = Paths.get(pathString);
    if (projectFilesystem.exists(path)) {
      return Optional.of(projectFilesystem.getPathForRelativePath(path));
    }
    throw new HumanReadableException(errorMsg + path);
  }

  /**
   * @param projectFilesystem The directory that is the root of the project being built.
   */
  @SafeVarargs
  public static BuckConfig createDefaultBuckConfig(
      ProjectFilesystem projectFilesystem,
      Platform platform,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, ImmutableMap<String, String>>... configOverrides)
      throws IOException {
    ImmutableList.Builder<File> configFileBuilder = ImmutableList.builder();

    File globalConfigDir = GLOBAL_BUCK_CONFIG_DIRECTORY_PATH.toFile();
    if (globalConfigDir.isDirectory()) {
      File globalConfigFiles[] = globalConfigDir.listFiles();
      Arrays.sort(globalConfigFiles);
      for (File globalConfigFile : globalConfigFiles) {
        configFileBuilder.add(globalConfigFile);
      }
    }
    File globalConfigFile = GLOBAL_BUCK_CONFIG_FILE_PATH.toFile();
    if (globalConfigFile.isFile()) {
      configFileBuilder.add(globalConfigFile);
    }

    Path homeDirectory = Paths.get(System.getProperty("user.home"));
    File userConfigDir = homeDirectory.resolve(DEFAULT_BUCK_CONFIG_DIRECTORY_NAME).toFile();
    if (userConfigDir.isDirectory()) {
      File userConfigFiles[] = userConfigDir.listFiles();
      Arrays.sort(userConfigFiles);
      for (File userConfigFile : userConfigFiles) {
        configFileBuilder.add(userConfigFile);
      }
    }
    File userConfigFile = homeDirectory.resolve(DEFAULT_BUCK_CONFIG_FILE_NAME).toFile();
    if (userConfigFile.isFile()) {
      configFileBuilder.add(userConfigFile);
    }

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
        environment,
        configOverrides);
  }

  public ImmutableSet<String> getSections() {
    return config.getSectionToEntries().keySet();
  }
}
