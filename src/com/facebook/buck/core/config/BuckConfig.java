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

import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.facebook.infer.annotation.PropagatesNullable;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.stream.Stream;

/** Structured representation of data read from a {@code .buckconfig} file. */
public class BuckConfig {

  private final Architecture architecture;

  private final Config config;

  private final ProjectFilesystem projectFilesystem;

  private final Platform platform;

  private final ImmutableMap<String, String> environment;

  private final ConfigViewCache<BuckConfig> viewCache =
      new ConfigViewCache<>(this, BuckConfig.class);

  private final Function<String, UnconfiguredBuildTargetView> buildTargetParser;

  private final int hashCode;

  public BuckConfig(
      Config config,
      ProjectFilesystem projectFilesystem,
      Architecture architecture,
      Platform platform,
      ImmutableMap<String, String> environment,
      Function<String, UnconfiguredBuildTargetView> buildTargetParser) {
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
      Function<String, UnconfiguredBuildTargetView> buildTargetParser) {
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

  public UnconfiguredBuildTargetView getUnconfiguredBuildTargetForFullyQualifiedTarget(
      String target) {
    return buildTargetParser.apply(target);
  }

  public BuildTarget getBuildTargetForFullyQualifiedTarget(
      String target, TargetConfiguration targetConfiguration) {
    return buildTargetParser.apply(target).configure(targetConfiguration);
  }

  public ImmutableList<BuildTarget> getFullyQualifiedBuildTargets(
      String section, String key, TargetConfiguration targetConfiguration) {
    ImmutableList<String> buildTargets = getListWithoutComments(section, key);
    if (buildTargets.isEmpty()) {
      return ImmutableList.of();
    }
    return buildTargets.stream()
        .map(buildTarget -> getBuildTargetForFullyQualifiedTarget(buildTarget, targetConfiguration))
        .collect(ImmutableList.toImmutableList());
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
    return getMaybeUnconfiguredBuildTarget(section, field)
        .map(target -> target.configure(targetConfiguration));
  }

  /**
   * @return the parsed UnconfiguredBuildTargetView in the given section and field, if set and a
   *     valid build target.
   *     <p>This is useful if you use getTool to get the target, if any, but allow filesystem
   *     references.
   */
  public Optional<UnconfiguredBuildTargetView> getMaybeUnconfiguredBuildTarget(
      String section, String field) {
    Optional<String> value = getValue(section, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    try {
      return Optional.of(getUnconfiguredBuildTargetForFullyQualifiedTarget(value.get()));
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
    return getMap(section, field).entrySet().stream()
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

  /** @return the path for the given section and property. */
  public Optional<Path> getPath(String sectionName, String name) {
    return getPath(sectionName, name, true);
  }

  public Path getRequiredPath(String section, String field) {
    Optional<Path> path = getPath(section, field);
    return getOrThrow(section, field, path);
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

  public Config getConfig() {
    return config;
  }

  public ProjectFilesystem getFilesystem() {
    return projectFilesystem;
  }
}
