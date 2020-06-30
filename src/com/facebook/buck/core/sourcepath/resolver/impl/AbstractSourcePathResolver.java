/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.sourcepath.resolver.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Abstract implementation of SourcePathResolver.
 *
 * <p>Most of the SourcePathResolverAdapter interface can be implemented in terms of just a few
 * functions ( the main requirement is resolving BuildTargetSourcePaths).
 *
 * <p>Existing code may expect to resolve each {@link SourcePath} to only one {@link Path}. In such
 * cases, {@link SourcePathResolverAdapter} is used to convert the resolver to return only one
 * {@link Path} per {@link SourcePath}.
 */
public abstract class AbstractSourcePathResolver implements SourcePathResolver {
  protected abstract ImmutableSortedSet<SourcePath> resolveDefaultBuildTargetSourcePath(
      DefaultBuildTargetSourcePath targetSourcePath);

  @Override
  public abstract String getSourcePathName(BuildTarget target, SourcePath sourcePath);

  protected abstract ProjectFilesystem getBuildTargetSourcePathFilesystem(
      BuildTargetSourcePath sourcePath);

  @Override
  public <T> ImmutableMap<T, ImmutableSortedSet<AbsPath>> getMappedPaths(
      Map<T, SourcePath> sourcePathMap) {
    ImmutableMap.Builder<T, ImmutableSortedSet<AbsPath>> paths = ImmutableMap.builder();
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
   * @return the {@link AbsPath} instances for this {@code sourcePath}, resolved using its
   *     associated {@link ProjectFilesystem}.
   */
  @Override
  public ImmutableSortedSet<AbsPath> getAbsolutePath(SourcePath sourcePath) {
    ImmutableSortedSet<Path> paths = getPathPrivateImpl(sourcePath);
    ImmutableSortedSet.Builder<AbsPath> builder =
        ImmutableSortedSet.orderedBy(AbsPath.comparator());
    for (Path path : paths) {
      if (path.isAbsolute()) {
        builder.add(AbsPath.of(path));
      } else if (sourcePath instanceof BuildTargetSourcePath) {
        builder.add(
            AbsPath.of(
                getBuildTargetSourcePathFilesystem((BuildTargetSourcePath) sourcePath)
                    .resolve(path)));
      } else if (sourcePath instanceof PathSourcePath) {
        builder.add(AbsPath.of(((PathSourcePath) sourcePath).getFilesystem().resolve(path)));
      } else {
        throw new UnsupportedOperationException(sourcePath.getClass() + " is not supported here!");
      }
    }
    return builder.build();
  }

  @Override
  public ImmutableSortedSet<AbsPath> getAllAbsolutePaths(
      Collection<? extends SourcePath> sourcePaths) {
    return sourcePaths.stream()
        .flatMap(sourcePath -> getAbsolutePath(sourcePath).stream())
        .collect(ImmutableSortedSet.toImmutableSortedSet(AbsPath.comparator()));
  }

  @Override
  public ImmutableSortedSet<RelPath> getAllRelativePaths(
      ProjectFilesystem projectFilesystem, Collection<? extends SourcePath> sourcePaths) {
    return sourcePaths.stream()
        .flatMap(sourcePath -> getCellUnsafeRelPath(projectFilesystem, sourcePath).stream())
        .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator()));
  }

  @Override
  public ImmutableSortedSet<RelPath> getCellUnsafeRelPath(SourcePath sourcePath) {
    ImmutableSortedSet<Path> toReturns = getPathPrivateImpl(sourcePath);

    return toReturns.stream()
        .map(RelPath::of)
        .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator()));
  }

  /**
   * @return The {@link Path} instances the {@code sourcePath} refers to, ideally relative to its
   *     owning {@link ProjectFilesystem}. Absolute path may get returned however!
   *     <p>We should make sure that {@link #getPathPrivateImpl} always returns a relative path
   *     after which we should simply call {@link #getCellUnsafeRelPath}. Until then we still need
   *     this nonsense.
   */
  @Override
  public ImmutableSortedSet<Path> getIdeallyRelativePath(SourcePath sourcePath) {
    return getPathPrivateImpl(sourcePath);
  }

  private ImmutableSortedSet<Path> getPathsPrivateImpl(ImmutableSortedSet<SourcePath> sourcePaths) {
    ImmutableSortedSet.Builder<Path> pathsBuilder = ImmutableSortedSet.naturalOrder();
    sourcePaths.forEach(sourcePath -> pathsBuilder.addAll(getPathPrivateImpl(sourcePath)));
    return pathsBuilder.build();
  }

  /**
   * @return the {@link SourcePath} as a list of {@link Path} instances, with no guarantee whether
   *     the return value is absolute or relative. This should never be exposed to users. A {@link
   *     SourcePath} may resolve into multiple {@link Path} instances if the associated build target
   *     has multiple outputs.
   */
  private ImmutableSortedSet<Path> getPathPrivateImpl(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return ImmutableSortedSet.of(((PathSourcePath) sourcePath).getRelativePath());
    } else if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
      return ImmutableSortedSet.of(((ExplicitBuildTargetSourcePath) sourcePath).getResolvedPath());
    } else if (sourcePath instanceof ForwardingBuildTargetSourcePath) {
      return getPathPrivateImpl(((ForwardingBuildTargetSourcePath) sourcePath).getDelegate());
    } else if (sourcePath instanceof DefaultBuildTargetSourcePath) {
      DefaultBuildTargetSourcePath targetSourcePath = (DefaultBuildTargetSourcePath) sourcePath;
      ImmutableSortedSet<SourcePath> paths = resolveDefaultBuildTargetSourcePath(targetSourcePath);
      return getPathsPrivateImpl(paths);
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
  public ImmutableSortedSet<RelPath> getCellUnsafeRelPath(
      ProjectFilesystem projectFilesystem, SourcePath sourcePath) {
    return getAbsolutePath(sourcePath).stream()
        .map(path -> projectFilesystem.relativize(path))
        .collect(ImmutableSortedSet.toImmutableSortedSet(RelPath.comparator()));
  }

  @Override
  public ImmutableMap<Path, AbsPath> createRelativeMap(
      Path basePath, Iterable<SourcePath> sourcePaths) {
    // The goal here is pretty simple.
    // 1. For a PathSourcePath (an explicit file reference in a BUCK file) that is a
    //   a. file, add it as a single entry at a path relative to this target's base path
    //   b. directory, add all its contents as paths relative to this target's base path
    // 2. For a BuildTargetSourcePath (an output of another rule) that is a
    //   a. file, add it as a single entry with just the filename
    //   b. directory, add all its as paths relative to that directory preceded by the directory
    // name
    //
    // Simplified: 1a and 1b add the item relative to the target's directory, 2a and 2b add the item
    // relative to its own parent.

    // TODO(cjhopman): We should remove 1a because we shouldn't allow specifying directories in
    // srcs.

    Map<Path, AbsPath> relativePathMap = new LinkedHashMap<>();

    for (SourcePath sourcePath : sourcePaths) {
      ProjectFilesystem filesystem = getFilesystem(sourcePath);
      ImmutableSortedSet<AbsPath> absolutePaths =
          getAbsolutePath(sourcePath).stream()
              .map(AbsPath::normalize)
              .collect(ImmutableSortedSet.toImmutableSortedSet(AbsPath.comparator()));
      for (AbsPath absolutePath : absolutePaths) {
        try {
          if (sourcePath instanceof PathSourcePath) {
            addPathToRelativePathMap(
                filesystem,
                relativePathMap,
                basePath,
                absolutePath,
                basePath.relativize(absolutePath.getPath()));
          } else {
            addPathToRelativePathMap(
                filesystem,
                relativePathMap,
                absolutePath.getPath().getParent(),
                absolutePath,
                absolutePath.getFileName());
          }
        } catch (IOException e) {
          throw new RuntimeException(
              String.format("Couldn't read directory [%s].", absolutePath.toString()), e);
        }
      }
    }

    return ImmutableMap.copyOf(relativePathMap);
  }

  private static void addPathToRelativePathMap(
      ProjectFilesystem filesystem,
      Map<Path, AbsPath> relativePathMap,
      Path basePath,
      AbsPath absolutePath,
      Path relativePath)
      throws IOException {
    if (Files.isDirectory(absolutePath.getPath())) {
      ImmutableSet<Path> files = filesystem.getFilesUnderPath(absolutePath.getPath());
      for (Path file : files) {
        AbsPath absoluteFilePath = AbsPath.of(filesystem.resolve(file).normalize());
        addToRelativePathMap(
            relativePathMap, basePath.relativize(absoluteFilePath.getPath()), absoluteFilePath);
      }
    } else {
      addToRelativePathMap(relativePathMap, relativePath, absolutePath);
    }
  }

  private static void addToRelativePathMap(
      Map<Path, AbsPath> relativePathMap, Path pathRelativeToBaseDir, AbsPath absoluteFilePath) {
    relativePathMap.compute(
        pathRelativeToBaseDir,
        (ignored, current) -> {
          if (current != null) {
            throw new HumanReadableException(
                "The file '%s' appears twice in the hierarchy",
                pathRelativeToBaseDir.getFileName());
          }
          return absoluteFilePath;
        });
  }
}
