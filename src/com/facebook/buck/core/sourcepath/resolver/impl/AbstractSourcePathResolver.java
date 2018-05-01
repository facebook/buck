/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.sourcepath.resolver.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Abstract implementation of SourcePathResolver.
 *
 * <p>Most of the SourcePathResolver interface can be implemented in terms of just a few functions (
 * the main requirement is resolving BuildTargetSourcePaths).
 */
public abstract class AbstractSourcePathResolver implements SourcePathResolver {
  protected abstract SourcePath resolveDefaultBuildTargetSourcePath(
      DefaultBuildTargetSourcePath targetSourcePath);

  @Override
  public abstract String getSourcePathName(BuildTarget target, SourcePath sourcePath);

  protected abstract ProjectFilesystem getBuildTargetSourcePathFilesystem(
      BuildTargetSourcePath sourcePath);

  @Override
  public <T> ImmutableMap<T, Path> getMappedPaths(Map<T, SourcePath> sourcePathMap) {
    ImmutableMap.Builder<T, Path> paths = ImmutableMap.builder();
    for (ImmutableMap.Entry<T, SourcePath> entry : sourcePathMap.entrySet()) {
      paths.put(entry.getKey(), getAbsolutePath(entry.getValue()));
    }
    return paths.build();
  }

  /** @return the {@link ProjectFilesystem} associated with {@code sourcePath}. */
  @Override
  public ProjectFilesystem getFilesystem(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return ((PathSourcePath) sourcePath).getFilesystem();
    }
    if (sourcePath instanceof BuildTargetSourcePath) {
      return getBuildTargetSourcePathFilesystem((BuildTargetSourcePath) sourcePath);
    }
    if (sourcePath instanceof ArchiveMemberSourcePath) {
      return getFilesystem(((ArchiveMemberSourcePath) sourcePath).getArchiveSourcePath());
    }
    throw new IllegalStateException();
  }

  /**
   * @return the {@link Path} for this {@code sourcePath}, resolved using its associated {@link
   *     ProjectFilesystem}.
   */
  @Override
  public Path getAbsolutePath(SourcePath sourcePath) {
    Path path = getPathPrivateImpl(sourcePath);
    if (path.isAbsolute()) {
      return path;
    }

    if (sourcePath instanceof BuildTargetSourcePath) {
      return getBuildTargetSourcePathFilesystem((BuildTargetSourcePath) sourcePath).resolve(path);
    } else if (sourcePath instanceof PathSourcePath) {
      return ((PathSourcePath) sourcePath).getFilesystem().resolve(path);
    } else {
      throw new UnsupportedOperationException(sourcePath.getClass() + " is not supported here!");
    }
  }

  @Override
  public ArchiveMemberPath getAbsoluteArchiveMemberPath(SourcePath sourcePath) {
    Preconditions.checkState(sourcePath instanceof ArchiveMemberSourcePath);
    ArchiveMemberSourcePath archiveMemberSourcePath = (ArchiveMemberSourcePath) sourcePath;

    Path archiveAbsolutePath = getAbsolutePath(archiveMemberSourcePath.getArchiveSourcePath());

    return ArchiveMemberPath.of(archiveAbsolutePath, archiveMemberSourcePath.getMemberPath());
  }

  @Override
  public ArchiveMemberPath getRelativeArchiveMemberPath(SourcePath sourcePath) {
    Preconditions.checkState(sourcePath instanceof ArchiveMemberSourcePath);
    ArchiveMemberSourcePath archiveMemberSourcePath = (ArchiveMemberSourcePath) sourcePath;

    Path archiveRelativePath = getRelativePath(archiveMemberSourcePath.getArchiveSourcePath());

    return ArchiveMemberPath.of(archiveRelativePath, archiveMemberSourcePath.getMemberPath());
  }

  @Override
  public ImmutableSortedSet<Path> getAllAbsolutePaths(
      Collection<? extends SourcePath> sourcePaths) {
    return sourcePaths
        .stream()
        .map(this::getAbsolutePath)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  /**
   * @return The {@link Path} the {@code sourcePath} refers to, relative to its owning {@link
   *     ProjectFilesystem}.
   */
  @Override
  public Path getRelativePath(SourcePath sourcePath) {
    Path toReturn = getPathPrivateImpl(sourcePath);

    Preconditions.checkState(
        !toReturn.isAbsolute(),
        "Expected path to be relative, not absolute: %s (from %s)",
        toReturn,
        sourcePath);

    return toReturn;
  }

  /**
   * @return The {@link Path} the {@code sourcePath} refers to, ideally relative to its owning
   *     {@link ProjectFilesystem}. Absolute path may get returned however!
   *     <p>We should make sure that {@link #getPathPrivateImpl} always returns a relative path
   *     after which we should simply call {@link #getRelativePath}. Until then we still need this
   *     nonsense.
   */
  @Override
  public Path getIdeallyRelativePath(SourcePath sourcePath) {
    return getPathPrivateImpl(sourcePath);
  }

  /**
   * @return the {@link SourcePath} as a {@link Path}, with no guarantee whether the return value is
   *     absolute or relative. This should never be exposed to users.
   */
  private Path getPathPrivateImpl(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return ((PathSourcePath) sourcePath).getRelativePath();
    } else if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
      return ((ExplicitBuildTargetSourcePath) sourcePath).getResolvedPath();
    } else if (sourcePath instanceof ForwardingBuildTargetSourcePath) {
      return getPathPrivateImpl(((ForwardingBuildTargetSourcePath) sourcePath).getDelegate());
    } else if (sourcePath instanceof DefaultBuildTargetSourcePath) {
      DefaultBuildTargetSourcePath targetSourcePath = (DefaultBuildTargetSourcePath) sourcePath;
      SourcePath path = resolveDefaultBuildTargetSourcePath(targetSourcePath);
      return getPathPrivateImpl(path);
    } else {
      throw new UnsupportedOperationException(sourcePath.getClass() + " is not supported here!");
    }
  }

  /**
   * Resolved the logical names for a group of SourcePath objects into a map, throwing an error on
   * duplicates.
   */
  @Override
  public ImmutableMap<String, SourcePath> getSourcePathNames(
      BuildTarget target, String parameter, Iterable<SourcePath> sourcePaths) {
    return getSourcePathNames(target, parameter, sourcePaths, x -> true, x -> x);
  }

  /**
   * Resolves the logical names for a group of objects that have a SourcePath into a map, throwing
   * an error on duplicates.
   */
  @Override
  public <T> ImmutableMap<String, T> getSourcePathNames(
      BuildTarget target,
      String parameter,
      Iterable<T> objects,
      Predicate<T> filter,
      Function<T, SourcePath> objectSourcePathFunction) {

    Map<String, T> resolved = new LinkedHashMap<>();

    for (T object : objects) {
      if (filter.test(object)) {
        SourcePath path = objectSourcePathFunction.apply(object);
        String name = getSourcePathName(target, path);
        T old = resolved.put(name, object);
        if (old != null) {
          throw new HumanReadableException(
              String.format(
                  "%s: parameter '%s': duplicate entries for '%s'", target, parameter, name));
        }
      }
    }

    return ImmutableMap.copyOf(resolved);
  }

  /**
   * Takes an {@link Iterable} of {@link SourcePath} objects and filters those that represent {@link
   * Path}s.
   */
  @Override
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
        .transform(PathSourcePath::getRelativePath)
        .toList();
  }

  @Override
  public ImmutableCollection<Path> filterInputsToCompareToOutput(SourcePath... sources) {
    return filterInputsToCompareToOutput(Arrays.asList(sources));
  }

  /** @return the {@link PathSourcePath} backing the given {@link SourcePath}, if any. */
  @Override
  public Optional<PathSourcePath> getPathSourcePath(SourcePath sourcePath) {
    if (sourcePath instanceof ArchiveMemberSourcePath) {
      sourcePath = ((ArchiveMemberSourcePath) sourcePath).getArchiveSourcePath();
    }
    return sourcePath instanceof PathSourcePath
        ? Optional.of((PathSourcePath) sourcePath)
        : Optional.empty();
  }

  @Override
  public Path getRelativePath(ProjectFilesystem projectFilesystem, SourcePath sourcePath) {
    return projectFilesystem.relativize(getAbsolutePath(sourcePath));
  }
}
