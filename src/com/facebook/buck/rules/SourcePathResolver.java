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

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

public class SourcePathResolver {

  private final BuildRuleResolver ruleResolver;

  public SourcePathResolver(BuildRuleResolver ruleResolver) {
    this.ruleResolver = ruleResolver;
  }

  /**
   * Use either {@link #getRelativePath(SourcePath)} or {@link #getAbsolutePath(SourcePath)}
   * depending on your needs.
   */
  public Path deprecatedGetPath(SourcePath sourcePath) {
    Preconditions.checkState(
        !(sourcePath instanceof ResourceSourcePath),
        "ResourceSourcePath is not supported by deprecatedGetPath.");
    return getPathPrivateImpl(sourcePath);
  }

  public ImmutableList<Path> deprecatedAllPaths(Iterable<? extends SourcePath> sourcePaths) {
    // Maintain ordering and duplication if necessary.
    return StreamSupport.stream(sourcePaths.spliterator(), false)
        .map(this::deprecatedGetPath)
        .collect(MoreCollectors.toImmutableList());
  }

  public <T> ImmutableMap<T, Path> getMappedPaths(Map<T, SourcePath> sourcePathMap) {
    ImmutableMap.Builder<T, Path> paths = ImmutableMap.builder();
    for (ImmutableMap.Entry<T, SourcePath> entry : sourcePathMap.entrySet()) {
      paths.put(entry.getKey(), getAbsolutePath(entry.getValue()));
    }
    return paths.build();
  }

  /**
   * @return the {@link ProjectFilesystem} associated with {@code sourcePath}.
   */
  public ProjectFilesystem getFilesystem(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return ((PathSourcePath) sourcePath).getFilesystem();
    }
    if (sourcePath instanceof BuildTargetSourcePath) {
      return getRuleOrThrow((BuildTargetSourcePath) sourcePath).getProjectFilesystem();
    }
    throw new IllegalStateException();
  }

  /**
   * @return the {@link Path} for this {@code sourcePath}, resolved using its associated
   *     {@link com.facebook.buck.io.ProjectFilesystem}.
   */
  public Path getAbsolutePath(SourcePath sourcePath) {
    if (sourcePath instanceof ResourceSourcePath) {
      return ((ResourceSourcePath) sourcePath).getAbsolutePath();
    }

    Path relative = getPathPrivateImpl(sourcePath);

    if (relative.isAbsolute()) {
      return relative;
    }

    Optional<BuildRule> rule = getRule(sourcePath);
    if (rule.isPresent()) {
      return rule.get().getProjectFilesystem().resolve(relative);
    }

    return ((PathSourcePath) sourcePath).getFilesystem().resolve(relative);
  }

  public ArchiveMemberPath getAbsoluteArchiveMemberPath(SourcePath sourcePath) {
    Preconditions.checkState(sourcePath instanceof ArchiveMemberSourcePath);
    ArchiveMemberSourcePath archiveMemberSourcePath = (ArchiveMemberSourcePath) sourcePath;

    Path archiveAbsolutePath = getAbsolutePath(archiveMemberSourcePath.getArchiveSourcePath());

    return ArchiveMemberPath.of(archiveAbsolutePath, archiveMemberSourcePath.getMemberPath());
  }

  public ArchiveMemberPath getRelativeArchiveMemberPath(SourcePath sourcePath) {
    Preconditions.checkState(sourcePath instanceof ArchiveMemberSourcePath);
    ArchiveMemberSourcePath archiveMemberSourcePath = (ArchiveMemberSourcePath) sourcePath;

    Path archiveRelativePath = getRelativePath(archiveMemberSourcePath.getArchiveSourcePath());

    return ArchiveMemberPath.of(archiveRelativePath, archiveMemberSourcePath.getMemberPath());
  }

  public ImmutableSortedSet<Path> getAllAbsolutePaths(
      Collection<? extends SourcePath> sourcePaths) {
    return sourcePaths.stream()
        .map(this::getAbsolutePath)
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  /**
   * @return The {@link Path} the {@code sourcePath} refers to, relative to its owning
   * {@link com.facebook.buck.io.ProjectFilesystem}.
   */
  public Path getRelativePath(SourcePath sourcePath) {
    Preconditions.checkState(!(sourcePath instanceof ResourceSourcePath));

    Path toReturn = getPathPrivateImpl(sourcePath);

    Preconditions.checkState(
        !toReturn.isAbsolute(),
        "Expected path to be relative, not absolute: %s (from %s)",
        toReturn,
        sourcePath);

    return toReturn;
  }

  /**
   * @return the {@link SourcePath} as a {@link Path}, with no guarantee whether the return value is
   *     absolute or relative. This should never be exposed to users.
   */
  private Path getPathPrivateImpl(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return ((PathSourcePath) sourcePath).getRelativePath();
    }

    Preconditions.checkArgument(sourcePath instanceof BuildTargetSourcePath);
    BuildTargetSourcePath buildTargetSourcePath = (BuildTargetSourcePath) sourcePath;
    Optional<Path> resolvedPath = buildTargetSourcePath.getResolvedPath();
    Path toReturn;
    if (resolvedPath.isPresent()) {
      toReturn = resolvedPath.get();
    } else {
      toReturn = ruleResolver.getRule(buildTargetSourcePath.getTarget()).getPathToOutput();
    }

    if (toReturn == null) {
      throw new HumanReadableException(
          "No known output for: %s",
          buildTargetSourcePath.getTarget());
    }

    return toReturn;
  }

  /**
   * @return An {@link Optional} containing the {@link BuildRule} whose output {@code sourcePath}
   *         refers to, or {@code absent} if {@code sourcePath} doesn't refer to the output of a
   *         {@link BuildRule}.
   */
  public Optional<BuildRule> getRule(SourcePath sourcePath) {
    if (sourcePath instanceof BuildTargetSourcePath) {
      return Optional.of(getRuleOrThrow((BuildTargetSourcePath) sourcePath));
    } else {
      return Optional.empty();
    }
  }

  /**
   * @return The {@link BuildRule} whose output {@code sourcePath} refers to its output.
   */
  public BuildRule getRuleOrThrow(BuildTargetSourcePath sourcePath) {
    return Preconditions.checkNotNull(ruleResolver.getRule(sourcePath.getTarget()));
  }

  /**
   * Resolved the logical names for a group of SourcePath objects into a map, throwing an
   * error on duplicates.
   */
  public ImmutableMap<String, SourcePath> getSourcePathNames(
      BuildTarget target,
      String parameter,
      Iterable<SourcePath> sourcePaths) {
    return getSourcePathNames(target, parameter, sourcePaths, Functions.identity());
  }

  /**
   * Resolves the logical names for a group of objects that have a SourcePath into a map,
   * throwing an error on duplicates.
   */
  public <T> ImmutableMap<String, T> getSourcePathNames(
      BuildTarget target,
      String parameter,
      Iterable<T> objects,
      Function<T, SourcePath> objectSourcePathFunction) {

    Map<String, T> resolved = Maps.newLinkedHashMap();

    for (T object : objects) {
      SourcePath path = objectSourcePathFunction.apply(object);
      String name = getSourcePathName(target, path);
      T old = resolved.put(name, object);
      if (old != null) {
        throw new HumanReadableException(String.format(
            "%s: parameter '%s': duplicate entries for '%s'",
            target,
            parameter,
            name));
      }
    }

    return ImmutableMap.copyOf(resolved);
  }

  public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
    Preconditions.checkArgument(!(sourcePath instanceof ArchiveMemberSourcePath));
    if (sourcePath instanceof BuildTargetSourcePath) {
      return getNameForBuildTargetSourcePath((BuildTargetSourcePath) sourcePath);
    }
    Preconditions.checkArgument(sourcePath instanceof PathSourcePath);
    Path path = ((PathSourcePath) sourcePath).getRelativePath();
    return MorePaths.relativize(target.getBasePath(), path).toString();
  }

  private String getNameForBuildTargetSourcePath(BuildTargetSourcePath sourcePath) {
    BuildRule rule = ruleResolver.getRule(sourcePath.getTarget());

    // If this build rule implements `HasOutputName`, then return the output name
    // it provides.
    if (rule instanceof HasOutputName) {
      HasOutputName hasOutputName = (HasOutputName) rule;
      return hasOutputName.getOutputName();
    }

    // If an explicit path is set, use it's relative path to the build rule's output location to
    // infer a unique name.
    Optional<Path> explicitPath = sourcePath.getResolvedPath();
    if (explicitPath.isPresent()) {
      Path path = explicitPath.get();
      if (path.startsWith(rule.getProjectFilesystem().getBuckPaths().getGenDir())) {
        path = rule.getProjectFilesystem().getBuckPaths().getGenDir().relativize(path);
      }
      if (path.startsWith(rule.getBuildTarget().getBasePath())) {
        return rule.getBuildTarget().getBasePath().relativize(path).toString();
      }
    }

    // Otherwise, fall back to using the short name of rule's build target.
    return rule.getBuildTarget().getShortName();
  }

  /**
   * Takes an {@link Iterable} of {@link SourcePath} objects and filters those that represent
   * {@link Path}s.
   */
  public ImmutableCollection<Path> filterInputsToCompareToOutput(
      Iterable<? extends SourcePath> sources) {
    // Currently, the only implementation of SourcePath that should be included in the Iterable
    // returned by getInputsToCompareToOutput() is FileSourcePath, so it is safe to filter by that
    // and then use .asReference() to get its path.
    //
    // BuildTargetSourcePath should not be included in the output because it refers to a generated
    // file, and generated files are not hashed as part of a RuleKey.
    return FluentIterable.from(sources)
        .filter(PathSourcePath.class)
        .transform(
            PathSourcePath::getRelativePath)
        .toList();
  }

  public ImmutableCollection<Path> filterInputsToCompareToOutput(SourcePath... sources) {
    return filterInputsToCompareToOutput(Arrays.asList(sources));
  }

  public ImmutableCollection<BuildRule> filterBuildRuleInputs(
      Iterable<? extends SourcePath> sources) {
    return FluentIterable.from(sources)
        .filter(BuildTargetSourcePath.class)
        .transform(
            input -> ruleResolver.getRule(input.getTarget()))
        .toList();
  }

  public ImmutableCollection<BuildRule> filterBuildRuleInputs(SourcePath... sources) {
    return filterBuildRuleInputs(Arrays.asList(sources));
  }

}
