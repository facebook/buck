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

import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.nio.file.Path;

/**
 * Represents a single checkout of a code base. Two repositories model the same code base if their
 * underlying {@link ProjectFilesystem}s are equal.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class Repository {

  @Value.Auxiliary
  @Value.Parameter
  public abstract Optional<String> getName();

  @Value.Parameter
  public abstract ProjectFilesystem getFilesystem();

  @Value.Auxiliary
  @Value.Parameter
  public abstract KnownBuildRuleTypes getKnownBuildRuleTypes();

  @Value.Parameter
  public abstract BuckConfig getBuckConfig();

  @Value.Auxiliary
  @Value.Parameter
  public abstract RepositoryFactory getRepositoryFactory();

  // TODO(jacko): This is a hack to avoid breaking the build. Get rid of it.
  @Value.Parameter
  public abstract AndroidDirectoryResolver getAndroidDirectoryResolver();

  public Description<?> getDescription(BuildRuleType type) {
    return getKnownBuildRuleTypes().getDescription(type);
  }

  public BuildRuleType getBuildRuleType(String rawType) {
    return getKnownBuildRuleTypes().getBuildRuleType(rawType);
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return getKnownBuildRuleTypes().getAllDescriptions();
  }

  @Value.Lazy
  public ImmutableMap<Optional<String>, Optional<String>> getLocalToCanonicalRepoNamesMap() {
    ImmutableMap.Builder<Optional<String>, Optional<String>> builder =
        ImmutableMap.builder();

    // Paths starting with "//" (i.e. no "@repo" prefix) always map to the name of the current
    // repo. For the root repo where buck is invoked, there is no name, and this mapping is a
    // no-op.
    builder.put(Optional.<String>absent(), getName());

    // Add mappings for repos listed in the [repositories] section of .buckconfig.
    ImmutableMap<String, Path> localNamePaths = getBuckConfig().getRepositoryPaths();
    ImmutableMap<Path, Optional<String>> canonicalPathNames =
        getRepositoryFactory().getCanonicalPathNames();
    for (String localName : localNamePaths.keySet()) {
      Path canonicalPath = localNamePaths.get(localName);
      Optional<String> canonicalName = canonicalPathNames.get(canonicalPath);
      Preconditions.checkNotNull(canonicalName);
      builder.put(Optional.of(localName), canonicalName);
    }
    return builder.build();
  }

  public BuildTargetParser getBuildTargetParser() {
    return new BuildTargetParser(getLocalToCanonicalRepoNamesMap());
  }

  public Path getAbsolutePathToBuildFile(BuildTarget target)
      throws MissingBuildFileException {
    Preconditions.checkArgument(
        target.getRepository().equals(getName()),
        "Target %s is not from this repository %s.",
        target,
        getName());
    Path relativePath = target.getBasePath().resolve(
        new ParserConfig(getBuckConfig()).getBuildFileName());
    if (!getFilesystem().isFile(relativePath)) {
      throw new MissingBuildFileException(target, getBuckConfig());
    }
    return getFilesystem().resolve(relativePath);
  }

  @SuppressWarnings("serial")
  public static class MissingBuildFileException extends BuildTargetException {
    public MissingBuildFileException(BuildTarget buildTarget, BuckConfig buckConfig) {
      super(String.format("No build file at %s when resolving target %s.",
          buildTarget.getBasePathWithSlash() + new ParserConfig(buckConfig).getBuildFileName(),
          buildTarget.getFullyQualifiedName()));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }
}
