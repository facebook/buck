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

package com.facebook.buck.core.sourcepath.resolver;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Adapter that makes {@link SourcePathResolver} compatible with client code that assumes each
 * {@link SourcePath} resolves to only one {@link Path}.
 *
 * <p>See also {@link com.facebook.buck.core.sourcepath.resolver.impl.AbstractSourcePathResolver}.
 */
public class SourcePathResolverAdapter {
  private final SourcePathResolver resolver;

  public SourcePathResolverAdapter(SourcePathResolver resolver) {
    this.resolver = resolver;
  }

  /** Returns the {@link Path} associated with the given {@link SourcePath}. */
  public Path getAbsolutePath(SourcePath sourcePath) {
    return Iterables.getOnlyElement(resolver.getAbsolutePath(sourcePath));
  }

  /**
   * Returns the {@link Path} associated with the given {@link SourcePath} relative to its owning
   * {@link ProjectFilesystem}.
   */
  public Path getRelativePath(SourcePath sourcePath) {
    return Iterables.getOnlyElement(resolver.getRelativePath(sourcePath));
  }

  /**
   * Returns the {@link Path} instances the given {@link SourcePath} refers to, ideally relative to
   * its owning {@link ProjectFilesystem}. Absolute path may get returned however!
   */
  public Path getIdeallyRelativePath(SourcePath sourcePath) {
    return Iterables.getOnlyElement(resolver.getIdeallyRelativePath(sourcePath));
  }

  /**
   * Returns the {@link Path} associated with the given {@link SourcePath} relative to the given
   * {@link ProjectFilesystem}.
   */
  public Path getRelativePath(ProjectFilesystem projectFilesystem, SourcePath sourcePath) {
    return Iterables.getOnlyElement(resolver.getRelativePath(projectFilesystem, sourcePath));
  }

  /** Returns the {@link Path} instances associated with the given {@link SourcePath} instances. */
  public ImmutableSortedSet<Path> getAllAbsolutePaths(
      Collection<? extends SourcePath> sourcePaths) {
    return resolver.getAllAbsolutePaths(sourcePaths);
  }

  /**
   * Returns a list of values and their associated {@link Path} instances by transforming the given
   * {@link SourcePath} instances into {@link Path} instances.
   */
  public <T> ImmutableMap<T, Path> getMappedPaths(Map<T, SourcePath> sourcePathMap) {
    ImmutableMap<T, ImmutableSortedSet<Path>> mappedPaths = resolver.getMappedPaths(sourcePathMap);
    ImmutableMap.Builder<T, Path> builder = new ImmutableMap.Builder<>();
    for (Map.Entry<T, ImmutableSortedSet<Path>> entry : mappedPaths.entrySet()) {
      builder.put(entry.getKey(), Iterables.getOnlyElement(entry.getValue()));
    }
    return builder.build();
  }

  /**
   * Resolves the logical names for a group of {@link SourcePath} objects into a map, throwing an
   * error on duplicates.
   */
  public ImmutableMap<String, SourcePath> getSourcePathNames(
      BuildTarget target, String parameter, Iterable<SourcePath> sourcePaths) {
    return resolver.getSourcePathNames(target, parameter, sourcePaths);
  }

  /**
   * Returns a collection of {@link Path} instances by filtering the given {@link SourcePath}
   * instances for {@link Path} instances.
   */
  public ImmutableCollection<Path> filterInputsToCompareToOutput(
      Iterable<? extends SourcePath> sources) {
    return resolver.filterInputsToCompareToOutput(sources);
  }

  /**
   * Resolves the logical names for a group of {@link SourcePath} objects into a map, throwing an
   * error on duplicates.
   */
  public <T> ImmutableMap<String, T> getSourcePathNames(
      BuildTarget target,
      String parameter,
      Iterable<T> objects,
      Predicate<T> filter,
      Function<T, SourcePath> objectSourcePathFunction) {
    return resolver.getSourcePathNames(
        target, parameter, objects, filter, objectSourcePathFunction);
  }

  /** Returns the logical name for a given {@link SourcePath}. */
  public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
    return resolver.getSourcePathName(target, sourcePath);
  }

  /**
   * Returns a map where given {@link SourcePath} instances are resolved relatively to the given
   * base path and stored (as keys) with their absolute paths (as values).
   */
  public ImmutableMap<Path, Path> createRelativeMap(
      Path basePath, Iterable<SourcePath> sourcePaths) {
    return resolver.createRelativeMap(basePath, sourcePaths);
  }

  /** Returns the {@link ProjectFilesystem} associated with this adapter. */
  public ProjectFilesystem getFilesystem(SourcePath sourcePath) {
    return resolver.getFilesystem(sourcePath);
  }

  /**
   * Returns the {@link com.facebook.buck.core.sourcepath.resolver.SourcePathResolver} associated
   * with this adapter.
   */
  public SourcePathResolver getResolver() {
    return resolver;
  }

  @Override
  public String toString() {
    return resolver.toString();
  }
}
