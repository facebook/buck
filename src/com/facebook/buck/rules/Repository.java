/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * Represents a single checkout of a code base. Two repositories model the same code base if their
 * underlying {@link ProjectFilesystem}s are equal.
 */
public class Repository {

  private final Optional<String> name;
  private final ProjectFilesystem filesystem;
  private final KnownBuildRuleTypes buildRuleTypes;
  private final BuckConfig buckConfig;
  private final RepositoryFactory repositoryFactory;

  @Nullable
  private volatile ImmutableMap<Optional<String>, Optional<String>> localToCanonicalRepoNamesMap;

  // TODO(jacko): This is a hack to avoid breaking the build. Get rid of it.
  public final AndroidDirectoryResolver androidDirectoryResolver;

  @VisibleForTesting
  public Repository(
      Optional<String> name,
      ProjectFilesystem filesystem,
      KnownBuildRuleTypes buildRuleTypes,
      BuckConfig buckConfig,
      RepositoryFactory repositoryFactory,
      AndroidDirectoryResolver androidDirectoryResolver) {
    this.name = Preconditions.checkNotNull(name);
    this.filesystem = Preconditions.checkNotNull(filesystem);
    this.buildRuleTypes = Preconditions.checkNotNull(buildRuleTypes);
    this.buckConfig = Preconditions.checkNotNull(buckConfig);
    this.repositoryFactory = Preconditions.checkNotNull(repositoryFactory);
    this.androidDirectoryResolver = Preconditions.checkNotNull(androidDirectoryResolver);

    localToCanonicalRepoNamesMap = null;
  }

  public Optional<String> getName() {
    return name;
  }

  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  public Description<?> getDescription(BuildRuleType type) {
    return buildRuleTypes.getDescription(type);
  }

  public BuildRuleType getBuildRuleType(String rawType) {
    return buildRuleTypes.getBuildRuleType(rawType);
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return buildRuleTypes.getAllDescriptions();
  }

  public KnownBuildRuleTypes getKnownBuildRuleTypes() {
    return buildRuleTypes;
  }

  public BuckConfig getBuckConfig() {
    return buckConfig;
  }

  @VisibleForTesting
  ImmutableMap<Optional<String>, Optional<String>> getLocalToCanonicalRepoNamesMap() {
    if (localToCanonicalRepoNamesMap != null) {
      return localToCanonicalRepoNamesMap;
    }

    synchronized (this) {
      // Double-check locking.
      if (localToCanonicalRepoNamesMap != null) {
        return localToCanonicalRepoNamesMap;
      }

      ImmutableMap.Builder<Optional<String>, Optional<String>> builder =
          ImmutableMap.builder();

      // Paths starting with "//" (i.e. no "@repo" prefix) always map to the name of the current
      // repo. For the root repo where buck is invoked, there is no name, and this mapping is a
      // no-op.
      builder.put(Optional.<String>absent(), name);

      // Add mappings for repos listed in the [repositories] section of .buckconfig.
      ImmutableMap<String, Path> localNamePaths = buckConfig.getRepositoryPaths();
      ImmutableMap<Path, Optional<String>> canonicalPathNames =
          repositoryFactory.getCanonicalPathNames();
      for (String localName : localNamePaths.keySet()) {
        Path canonicalPath = localNamePaths.get(localName);
        Optional<String> canonicalName = canonicalPathNames.get(canonicalPath);
        Preconditions.checkNotNull(canonicalName);
        builder.put(Optional.of(localName), canonicalName);
      }
      localToCanonicalRepoNamesMap = builder.build();
      return localToCanonicalRepoNamesMap;
    }
  }

  public BuildTargetParser getBuildTargetParser() {
    return new BuildTargetParser(getLocalToCanonicalRepoNamesMap());
  }

  @Override
  public String toString() {
    return String.format("<Repository (%s)>", filesystem.getRootPath());
  }

  @Override
  public int hashCode() {
    return filesystem.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Repository)) {
      return false;
    }

    Repository that = (Repository) obj;
    return this.getFilesystem().equals(that.getFilesystem()) &&
        this.getBuckConfig().equals(that.getBuckConfig());
  }

  public Path getAbsolutePathToBuildFile(BuildTarget target)
      throws MissingBuildFileException {
    Preconditions.checkArgument(
        target.getRepository().equals(name),
        "Target %s is not from this repository %s.",
        target,
        name);
    Path relativePath = target.getBuildFilePath();
    if (!filesystem.isFile(relativePath)) {
      throw new MissingBuildFileException(target);
    }
    return filesystem.resolve(relativePath);
  }

  @SuppressWarnings("serial")
  public static class MissingBuildFileException extends BuildTargetException {
    public MissingBuildFileException(BuildTarget buildTarget) {
      super(String.format("No build file at %s when resolving target %s.",
          buildTarget.getBasePathWithSlash() + BuckConstant.BUILD_RULES_FILE_NAME,
          buildTarget.getFullyQualifiedName()));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }
}
