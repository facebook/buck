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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.CassandraArtifactCache;
import com.facebook.buck.rules.DirArtifactCache;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;

import org.ini4j.Ini;
import org.ini4j.Profile.Section;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.List;
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

  private static final BuckConfig EMPTY_INSTANCE = new BuckConfig(
      ImmutableMap.<String, Map<String, String>>of(), null /* buildTargetParser */);

  private static final String DEFAULT_CACHE_DIR = "buck-cache";
  private static final String DEFAULT_CASSANDRA_PORT = "9160";

  private final ImmutableMap<String, ImmutableMap<String, String>> sectionsToEntries;

  private final ImmutableMap<String, BuildTarget> aliasToBuildTargetMap;

  @Nullable
  private final BuildTargetParser buildTargetParser;

  private enum ArtifactCacheNames {
    noop,
    dir,
    cassandra
  }
  private final ArtifactCache artifactCache;

  @VisibleForTesting
  BuckConfig(Map<String, Map<String, String>> sectionsToEntries,
      BuildTargetParser buildTargetParser) {
    Preconditions.checkNotNull(sectionsToEntries);
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
    this.artifactCache = initArtifactCache();
  }

  public static BuckConfig emptyConfig() {
    return EMPTY_INSTANCE;
  }

  public static BuckConfig createFromFile(File file) throws IOException {
    Preconditions.checkNotNull(file);

    // It is necessary to get the absolute file before getting the parent file because the file
    // is most likely `new File(".buckproject")`, whose parent file is null, according to Java.
    File projectRoot = file.getAbsoluteFile().getParentFile();
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectRoot);
    BuildTargetParser buildTargetParser = new BuildTargetParser(projectFilesystem);

    BufferedReader reader = Files.newReader(file, Charsets.UTF_8);
    return createFromReader(reader, buildTargetParser);
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
  static Map<String, Map<String, String>> createFromReader(Reader reader) throws IOException {
    Preconditions.checkNotNull(reader);

    Ini ini = new Ini();
    ini.load(reader);

    Map<String, Map<String, String>> sectionsToEntries = Maps.newHashMap();
    for (String sectionName : ini.keySet()) {
      ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
      Section section = ini.get(sectionName);
      for (String propertyName : section.keySet()) {
        // Verify that a section does not have the same key specified more than once.
        if (section.getAll(propertyName).size() > 1) {
          throw new HumanReadableException("Duplicate definition for %s in [%s].",
              propertyName,
              sectionName);
        }

        String propertyValue = section.get(propertyName);
        builder.put(propertyName, propertyValue);
      }

      ImmutableMap<String, String> sectionToEntries = builder.build();
      sectionsToEntries.put(sectionName, sectionToEntries);
    }

    return sectionsToEntries;
  }

  @VisibleForTesting
  static BuckConfig createFromReader(Reader reader, BuildTargetParser buildTargetParser)
      throws IOException {
    Map<String, Map<String, String>> sectionsToEntries = createFromReader(reader);
    return new BuckConfig(sectionsToEntries, buildTargetParser);
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
   * A [possibly empty] sequence of paths to files that should be included by default when
   * evaluating a build file.
   */
  public Iterable<String> getDefaultIncludes() {
    ImmutableMap<String, String> entries = getEntriesForSection("buildfile");
    String includes = Strings.nullToEmpty(entries.get("includes"));
    return Splitter.on(' ').trimResults().omitEmptyStrings().split(includes);
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
    Preconditions.checkNotNull(buildTargetParser,
        "buildTargetParser should be set for all instances of BuckConfig except EMPTY_INSTANCE");

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

  Optional<String> getPathToDefaultAndroidManifest() {
    return getValue("project", "default_android_manifest");
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

  List<String> getInitialTargets() {
    Optional<String> initialTargets = getValue("project", "initial_targets");
    return initialTargets.isPresent()
        ? Lists.newArrayList(Splitter.on(' ').trimResults().split(initialTargets.get()))
        : ImmutableList.<String>of();
  }

  private ArtifactCache initNoopArtifactCache() {
    return new NoopArtifactCache();
  }

  private ArtifactCache initDirArtifactCache() {
    String cacheDir = getValue("cache", "dir").or(DEFAULT_CACHE_DIR);
    File dir = new File(cacheDir);
    try {
      return new DirArtifactCache(dir);
    } catch (IOException e) {
      throw new HumanReadableException(String.format(
          "Failure initializing artifact cache directory: %s",
          dir));
    }
  }

  private ArtifactCache initCassandraArtifactCache() {
    // cache.hosts
    String cacheHosts = getValue("cache", "hosts").or("");
    // cache.port
    int port = Integer.parseInt(getValue("cache", "port").or(DEFAULT_CASSANDRA_PORT));

    try {
      return new CassandraArtifactCache(cacheHosts, port);
    } catch (ConnectionException e) {
      throw new HumanReadableException("Cassandra cache connection failure");
    }
  }

  private ArtifactCache initArtifactCache() {
    String cacheMode = getValue("cache", "mode").or("noop");
    try {
      switch (ArtifactCacheNames.valueOf(cacheMode)) {
      case noop:
        return initNoopArtifactCache();
      case dir:
        return initDirArtifactCache();
      case cassandra:
        return initCassandraArtifactCache();
      }
    } catch (IllegalArgumentException e) {
      throw new HumanReadableException(String.format("Unusable cache.mode: '%s'", cacheMode));
    }

    throw new HumanReadableException(String.format("Unusable cache.mode: '%s'", cacheMode));
  }

  public ArtifactCache getArtifactCache() {
    return artifactCache;
  }

  private Optional<String> getValue(String sectionName, String propertyName) {
    ImmutableMap<String, String> properties = this.getEntriesForSection(sectionName);
    String value = properties.get(propertyName);
    if (value != null) {
      return Optional.of(value);
    } else {
      return Optional.absent();
    }
  }
}
