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
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Structured representation of data read from a {@code .buckconfig} file.
 */
@Immutable
class BuckConfig {
  private static final String ALIAS_SECTION_HEADER = "alias";

  /**
   * This pattern is designed so that a fully-qualified build target cannot be a valid alias name
   * and vice-versa.
   */
  private static final Pattern ALIAS_PATTERN = Pattern.compile("[a-zA-Z_-][a-zA-Z0-9_-]*");

  @VisibleForTesting
  static final String BUCK_BUCKD_DIR_KEY = "buck.buckd_dir";

  private static final String DEFAULT_CACHE_DIR = "buck-cache";
  private static final String DEFAULT_CASSANDRA_PORT = "9160";
  private static final String DEFAULT_CASSANDRA_MODE = CassandraMode.readwrite.name();

  private final ImmutableMap<String, ImmutableMap<String, String>> sectionsToEntries;

  private final ImmutableMap<String, BuildTarget> aliasToBuildTargetMap;

  private final ProjectFilesystem projectFilesystem;

  private final BuildTargetParser buildTargetParser;

  private enum ArtifactCacheNames {
    dir,
    cassandra
  }

  private enum CassandraMode {
    readonly(false),
    readwrite(true),
    ;

    private final boolean doStore;

    private CassandraMode(boolean doStore) {
      this.doStore = doStore;
    }
  }

  @VisibleForTesting
  BuckConfig(Map<String, Map<String, String>> sectionsToEntries,
      ProjectFilesystem projectFilesystem,
      BuildTargetParser buildTargetParser) {
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
  }

  /**
   * Takes a sequence of {@code .buckconfig} files and loads them, in order, to create a
   * {@code BuckConfig} object. Each successive file that is loaded has the ability to override
   * definitions from a previous file.
   * @param projectFilesystem project for which the {@link BuckConfig} is being created.
   * @param files The sequence of {@code .buckconfig} files to load.
   */
  public static BuckConfig createFromFiles(ProjectFilesystem projectFilesystem,
      Iterable<File> files)
      throws IOException {
    Preconditions.checkNotNull(projectFilesystem);
    Preconditions.checkNotNull(files);
    BuildTargetParser buildTargetParser = new BuildTargetParser(projectFilesystem);

    if (Iterables.isEmpty(files)) {
      return new BuckConfig(
          ImmutableMap.<String, Map<String, String>>of(),
          projectFilesystem,
          buildTargetParser);
    }

    // Convert the Files to Readers.
    ImmutableList.Builder<Reader> readers = ImmutableList.builder();
    for (File file : files) {
      readers.add(Files.newReader(file, Charsets.UTF_8));
    }
    return createFromReaders(readers.build(), projectFilesystem, buildTargetParser);
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
  static BuckConfig createFromReader(
      Reader reader,
      ProjectFilesystem projectFilesystem,
      BuildTargetParser buildTargetParser)
      throws IOException {
    return createFromReaders(ImmutableList.of(reader), projectFilesystem, buildTargetParser);
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
      BuildTargetParser buildTargetParser)
      throws IOException {
    Map<String, Map<String, String>> sectionsToEntries = createFromReaders(readers);
    return new BuckConfig(sectionsToEntries, projectFilesystem, buildTargetParser);
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
   * affect either (buck-out, .idea, .buckd, buck-cache, .git, etc.).
   */
  public ImmutableSet<String> getIgnorePaths() {
    final ImmutableMap<String, String> projectConfig = getEntriesForSection("project");
    final String IGNORE_KEY = "ignore";
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    builder.add(BuckConstant.BUCK_OUTPUT_DIRECTORY);
    builder.add(".idea");

    // Take care not to ignore absolute paths.
    String buckdDir = System.getProperty(BUCK_BUCKD_DIR_KEY, ".buckd");
    String cacheDir = getCacheDir();
    for (String path : ImmutableList.of(buckdDir, cacheDir)) {
      if (!path.isEmpty() && path.charAt(0) != '/') {
        builder.add(path);
      }
    }

    if (projectConfig.containsKey(IGNORE_KEY)) {
      builder.addAll(Splitter.on(',')
          .omitEmptyStrings()
          .trimResults()
          .split(projectConfig.get(IGNORE_KEY)));
    }

    // Normalize paths in order to eliminate trailing '/' characters and whatnot.
    return ImmutableSet.<String>builder().addAll(Iterables.transform(builder.build(),
        new Function<String, String>() {
      @Override
      public String apply(String path) {
        return new File(path).getPath();
      }
    })).build();
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

  public long getDefaultTestTimeoutMillis() {
    return Long.parseLong(getValue("test", "timeout").or("0"));
  }

  @VisibleForTesting
  DefaultJavaPackageFinder createDefaultJavaPackageFinder() {
    Optional<String> srcRootsOptional = getValue("java", "src_roots");
    ImmutableSet<String> paths;
    if (srcRootsOptional.isPresent()) {
      String srcRoots = srcRootsOptional.get();
      Splitter splitter = Splitter.on(',').omitEmptyStrings().trimResults();
      paths = ImmutableSet.copyOf(splitter.split(srcRoots));
    } else {
      paths = ImmutableSet.of();
    }
    return DefaultJavaPackageFinder.createDefaultJavaPackageFinder(paths);
  }

  ImmutableSet<String> getDefaultExcludedLabels() {
    Optional<String> excludedRulesOptional = getValue("test", "excluded_labels");
    if (excludedRulesOptional.isPresent()) {
      String excludedRules = excludedRulesOptional.get();
      Splitter splitter = Splitter.on(',').omitEmptyStrings().trimResults();
      ImmutableSet<String> result =  ImmutableSet.copyOf(splitter.split(excludedRules));
      // Validate that all specified labels are valid.
      for (String label : result) {
        validateLabelName(label);
      }
      return result;
    } else {
      return ImmutableSet.of();
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

  public Ansi createAnsi() {
    String color = getValue("color", "ui").or("auto");
    switch (color) {
      case "false":
        return Ansi.withoutTty();
      // Git also supports "always" and "never" for color.ui:
      // https://www.kernel.org/pub/software/scm/git/docs/git-config.html
      // It would be nice to be able to mirror those nuances.
      case "auto":
      default:
        return new Ansi(Platform.detect());
    }
  }

  public ArtifactCache createArtifactCache(Console console) {
    String cacheMode = getValue("cache", "mode").or("");
    if (cacheMode.isEmpty()) {
      return new NoopArtifactCache();
    }
    ImmutableList.Builder<ArtifactCache> builder = ImmutableList.builder();
    Iterable<String> modes = Splitter.on(',').trimResults().split(cacheMode);
    try {
      for (String mode : modes) {
        switch (ArtifactCacheNames.valueOf(mode)) {
        case dir:
          builder.add(createDirArtifactCache());
          break;
        case cassandra:
          ArtifactCache cassandraArtifactCache = createCassandraArtifactCache(console);
          if (cassandraArtifactCache != null) {
            builder.add(cassandraArtifactCache);
          }
          break;
        }
      }
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException("Unusable cache.mode: '%s'", cacheMode);
    }
    ImmutableList<ArtifactCache> artifactCaches = builder.build();
    if (artifactCaches.size() == 1) {
      // Don't bother wrapping a single artifact cache in MultiArtifactCache.
      return artifactCaches.get(0);
    } else {
      return new MultiArtifactCache(artifactCaches);
    }
  }

  @VisibleForTesting
  String getCacheDir() {
    String cacheDir = getValue("cache", "dir").or(DEFAULT_CACHE_DIR);
    if (!cacheDir.isEmpty() && cacheDir.charAt(0) == '/') {
      return cacheDir;
    }
    return projectFilesystem.getPathRelativizer().apply(cacheDir);
  }

  private ArtifactCache createDirArtifactCache() {
    String cacheDir = getCacheDir();
    File dir = new File(cacheDir);
    try {
      return new DirArtifactCache(dir);
    } catch (IOException e) {
      throw new HumanReadableException("Failure initializing artifact cache directory: %s", dir);
    }
  }

  @Nullable
  private ArtifactCache createCassandraArtifactCache(Console console) {
    // cache.cassandra_mode
    String cacheCassandraMode = getValue("cache", "cassandra_mode").or(DEFAULT_CASSANDRA_MODE);
    final boolean doStore;
    try {
      doStore = CassandraMode.valueOf(cacheCassandraMode).doStore;
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException("Unusable cache.cassandra_mode: '%s'", cacheCassandraMode);
    }
    // cache.hosts
    String cacheHosts = getValue("cache", "hosts").or("");
    // cache.port
    int port = Integer.parseInt(getValue("cache", "port").or(DEFAULT_CASSANDRA_PORT));

    try {
      return new CassandraArtifactCache(cacheHosts, port, doStore);
    } catch (ConnectionException e) {
      console.getStdErr().println(String.format("Cassandra cache connection failure: %s",
          e.getMessage()));
      return null;
    }
  }

  public Optional<String> getMinimumNdkVersion() {
    return getValue("ndk", "min_version");
  }

  public Optional<String> getMaximumNdkVersion() {
    return getValue("ndk", "max_version");
  }

  public Optional<String> getValue(String sectionName, String propertyName) {
    ImmutableMap<String, String> properties = this.getEntriesForSection(sectionName);
    return Optional.fromNullable(properties.get(propertyName));
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
}
