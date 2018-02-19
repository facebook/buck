/*
 * Copyright 2017-present Facebook, Inc.
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
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.util.HumanReadableException;
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

public class DefaultSourcePathResolver implements SourcePathResolver {

  private final SourcePathRuleFinder ruleFinder;

  DefaultSourcePathResolver(SourcePathRuleFinder ruleFinder) {
    this.ruleFinder = ruleFinder;
  }

  public static DefaultSourcePathResolver from(SourcePathRuleFinder ruleFinder) {
    return new DefaultSourcePathResolver(ruleFinder);
  }

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
      return ruleFinder.getRule((BuildTargetSourcePath) sourcePath).getProjectFilesystem();
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
      BuildRule rule = ruleFinder.getRule((BuildTargetSourcePath) sourcePath);
      return rule.getProjectFilesystem().resolve(path);
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
      SourcePath path = ruleFinder.getRule(targetSourcePath).getSourcePathToOutput();
      if (path == null) {
        throw new HumanReadableException("No known output for: %s", targetSourcePath.getTarget());
      }
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

  @Override
  public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
    Preconditions.checkArgument(!(sourcePath instanceof ArchiveMemberSourcePath));
    if (sourcePath instanceof BuildTargetSourcePath) {
      BuildRule rule = ruleFinder.getRule((BuildTargetSourcePath) sourcePath);
      if (rule instanceof HasOutputName) {
        HasOutputName hasOutputName = (HasOutputName) rule;
        return hasOutputName.getOutputName();
      }
      if (sourcePath instanceof ForwardingBuildTargetSourcePath) {
        ForwardingBuildTargetSourcePath castPath = (ForwardingBuildTargetSourcePath) sourcePath;
        return getSourcePathName(target, castPath.getDelegate());
      } else if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
        Path path = ((ExplicitBuildTargetSourcePath) sourcePath).getResolvedPath();
        if (path.startsWith(rule.getProjectFilesystem().getBuckPaths().getGenDir())) {
          path = rule.getProjectFilesystem().getBuckPaths().getGenDir().relativize(path);
        }
        if (path.startsWith(rule.getBuildTarget().getBasePath())) {
          return rule.getBuildTarget().getBasePath().relativize(path).toString();
        }
      }
      return rule.getBuildTarget().getShortName();
    }
    Preconditions.checkArgument(sourcePath instanceof PathSourcePath);
    Path path = ((PathSourcePath) sourcePath).getRelativePath();
    return MorePaths.relativize(target.getBasePath(), path).toString();
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
}
