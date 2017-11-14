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

package com.facebook.buck.rules.modern;

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class DefaultInputPathResolver implements InputPathResolver {
  // TODO(cjhopman): Make this be restricted to resolving/extracting paths that are specified in
  // fields of the Buildable.
  private final SourcePathResolver pathResolver;
  private final Supplier<LimitedSourcePathResolver> limitedSourcePathResolver;

  public DefaultInputPathResolver(SourcePathResolver pathResolver) {
    this.pathResolver = pathResolver;
    this.limitedSourcePathResolver = MoreSuppliers.memoize(LimitedSourcePathResolver::new);
  }

  @Override
  public Path resolvePath(InputPath inputPath) {
    return pathResolver.getAbsolutePath(inputPath.getSourcePath());
  }

  @Override
  public Stream<Path> resolveAllPaths(Iterable<InputPath> inputPaths) {
    return RichStream.from(inputPaths).map(this::resolvePath);
  }

  private SourcePath extract(SourcePath path) {
    Preconditions.checkArgument(path instanceof LimitedSourcePath, "Expected LimitedSourcePath");
    return ((LimitedSourcePath) path).getLimitedSourcePath();
  }

  @Override
  public SourcePathResolver getLimitedSourcePathResolver() {
    return limitedSourcePathResolver.get();
  }

  private class LimitedSourcePathResolver implements SourcePathResolver {
    @Override
    public <T> ImmutableMap<T, Path> getMappedPaths(Map<T, SourcePath> sourcePathMap) {
      return pathResolver.getMappedPaths(
          sourcePathMap
              .entrySet()
              .stream()
              .collect(
                  MoreCollectors.toImmutableMap(
                      entry -> entry.getKey(), entry -> extract(entry.getValue()))));
    }

    @Override
    public ProjectFilesystem getFilesystem(SourcePath sourcePath) {
      return pathResolver.getFilesystem(extract(sourcePath));
    }

    @Override
    public Path getAbsolutePath(SourcePath sourcePath) {
      return pathResolver.getAbsolutePath(extract(sourcePath));
    }

    @Override
    public ArchiveMemberPath getAbsoluteArchiveMemberPath(SourcePath sourcePath) {
      return pathResolver.getAbsoluteArchiveMemberPath(extract(sourcePath));
    }

    @Override
    public ArchiveMemberPath getRelativeArchiveMemberPath(SourcePath sourcePath) {
      return pathResolver.getRelativeArchiveMemberPath(extract(sourcePath));
    }

    @Override
    public ImmutableSortedSet<Path> getAllAbsolutePaths(
        Collection<? extends SourcePath> sourcePaths) {
      return pathResolver.getAllAbsolutePaths(
          sourcePaths
              .stream()
              .map(DefaultInputPathResolver.this::extract)
              .collect(MoreCollectors.toImmutableList()));
    }

    @Override
    public Path getRelativePath(SourcePath sourcePath) {
      return pathResolver.getRelativePath(extract(sourcePath));
    }

    @Override
    public Path getIdeallyRelativePath(SourcePath sourcePath) {
      return pathResolver.getIdeallyRelativePath(extract(sourcePath));
    }

    @Override
    public ImmutableMap<String, SourcePath> getSourcePathNames(
        BuildTarget target, String parameter, Iterable<SourcePath> sourcePaths) {
      return pathResolver.getSourcePathNames(
          target,
          parameter,
          RichStream.from(sourcePaths)
              .map(DefaultInputPathResolver.this::extract)
              .toOnceIterable());
    }

    @Override
    public <T> ImmutableMap<String, T> getSourcePathNames(
        BuildTarget target,
        String parameter,
        Iterable<T> objects,
        Predicate<T> filter,
        Function<T, SourcePath> objectSourcePathFunction) {
      return pathResolver.getSourcePathNames(
          target, parameter, objects, filter, (T t) -> extract(objectSourcePathFunction.apply(t)));
    }

    @Override
    public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
      return pathResolver.getSourcePathName(target, extract(sourcePath));
    }

    @Override
    public ImmutableCollection<Path> filterInputsToCompareToOutput(
        Iterable<? extends SourcePath> sources) {
      return pathResolver.filterInputsToCompareToOutput(
          RichStream.from(sources).map(DefaultInputPathResolver.this::extract).toOnceIterable());
    }

    @Override
    public ImmutableCollection<Path> filterInputsToCompareToOutput(SourcePath... sources) {
      return pathResolver.filterInputsToCompareToOutput(
          RichStream.from(sources).map(DefaultInputPathResolver.this::extract).toOnceIterable());
    }

    @Override
    public Optional<PathSourcePath> getPathSourcePath(SourcePath sourcePath) {
      return pathResolver.getPathSourcePath(extract(sourcePath));
    }
  }
}
